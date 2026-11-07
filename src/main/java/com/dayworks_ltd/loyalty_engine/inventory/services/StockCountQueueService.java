package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.enums.CountTrigger;
import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryTransaction;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.CloseDayRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryStockAuditRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.SaleTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.StockCountLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds the end-of-day blind count queue for a merchant.
 *
 * Every active item is on the list, in this order:
 *  1. MOVEMENT_BASED — items sold since the last count, highest units sold first.
 *  2. MOVEMENT_BASED — items received since the last count (restock / transfer) but not sold.
 *  3. RISK_SAMPLED   — everything else, longest without a count first.
 * (Counting only a random sample of unmoved items let stock go missing unnoticed between counts.)
 */
@Service
@RequiredArgsConstructor
public class StockCountQueueService {

    private final InventoryRepository inventoryRepository;
    private final SaleTransactionRepository saleTransactionRepository;
    private final InventoryStockAuditRepository auditRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final StockCountLineRepository stockCountLineRepository;
    private final CloseDayRepository closeDayRepository;

    @Transactional
    public List<StockCountLine> buildQueueForCloseDay(CloseDay closeDay) {
        List<StockCountLine> queue = plannedLines(closeDay, Set.of());
        for (int i = 0; i < queue.size(); i++) {
            queue.get(i).setPriorityRank(i + 1);
        }
        return stockCountLineRepository.saveAll(queue);
    }

    /**
     * Adds items missing from an open count: items added to stock or sold after the list was made,
     * and (for counts opened before every item was listed) the unmoved items. Returns how many were added.
     */
    @Transactional
    public int addMissingItems(CloseDay closeDay, List<StockCountLine> existing) {
        Set<String> listed = new HashSet<>();
        int maxRank = 0;
        for (StockCountLine l : existing) {
            listed.add(l.getItemCode());
            if (l.getPriorityRank() != null) maxRank = Math.max(maxRank, l.getPriorityRank());
        }
        List<StockCountLine> missing = plannedLines(closeDay, listed);
        for (StockCountLine l : missing) {
            l.setPriorityRank(++maxRank);
        }
        stockCountLineRepository.saveAll(missing);
        return missing.size();
    }

    /** The count list in order, leaving out item codes already listed. */
    private List<StockCountLine> plannedLines(CloseDay closeDay, Set<String> alreadyListed) {
        String merchantId = closeDay.getMerchantId();
        LocalDate tradingDate = closeDay.getTradingDate();
        Map<String, Inventory> stockByCode = currentStockByItemCode(merchantId);
        Movement movement = movementSinceLastCount(merchantId, tradingDate);

        List<StockCountLine> queue = new ArrayList<>();
        Set<String> queued = new HashSet<>(alreadyListed);

        // 1. Sold since the last count, fastest movers first
        for (Map.Entry<String, Integer> sold : movement.sold().entrySet()) {
            Inventory stock = stockByCode.get(sold.getKey());
            if (stock == null || !queued.add(sold.getKey())) continue;
            queue.add(buildLine(closeDay, stock, CountTrigger.MOVEMENT_BASED, sold.getValue(),
                    "Sold " + sold.getValue() + " since last count"));
        }

        // 2. Received but not sold — receipts are a common place for stock to go missing
        for (String itemCode : movement.received()) {
            Inventory stock = stockByCode.get(itemCode);
            if (stock == null || !queued.add(itemCode)) continue;
            queue.add(buildLine(closeDay, stock, CountTrigger.MOVEMENT_BASED, 0, "Stock received since last count"));
        }

        // 3. Everything else, longest without a count first (never counted at the very top)
        Map<String, LocalDateTime> lastCounted = lastCountedAtByItem(merchantId);
        List<Inventory> rest = stockByCode.values().stream()
                .filter(stock -> !queued.contains(stock.getItemCode()))
                .filter(stock -> !Boolean.FALSE.equals(stock.getIsActive()))
                .sorted(Comparator
                        .comparing((Inventory stock) -> lastCounted.get(stock.getItemCode()),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(stock -> stock.getItemName() == null ? "" : stock.getItemName().toLowerCase()))
                .toList();
        for (Inventory stock : rest) {
            LocalDateTime last = lastCounted.get(stock.getItemCode());
            String reason = last == null
                    ? "Not moved, never counted"
                    : "Not moved, last counted " + ChronoUnit.DAYS.between(last.toLocalDate(), tradingDate) + " days ago";
            queued.add(stock.getItemCode());
            queue.add(buildLine(closeDay, stock, CountTrigger.RISK_SAMPLED, 0, reason));
        }
        return queue;
    }

    /**
     * Items that moved since the last count — sold (fastest first) or received — with no quantities,
     * so the counting screen can show them before the count starts.
     */
    public List<Map<String, Object>> movedItems(String merchantId, LocalDate tradingDate) {
        Map<String, Inventory> stockByCode = currentStockByItemCode(merchantId);
        Movement movement = movementSinceLastCount(merchantId, tradingDate);
        List<Map<String, Object>> moved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : movement.sold().keySet()) {
            Inventory stock = stockByCode.get(code);
            if (stock != null && seen.add(code)) moved.add(movedView(stock, "SOLD"));
        }
        for (String code : movement.received()) {
            Inventory stock = stockByCode.get(code);
            if (stock != null && seen.add(code)) moved.add(movedView(stock, "RECEIVED"));
        }
        return moved;
    }

    /** Units sold per item (fastest first) and items received in the window. */
    private record Movement(LinkedHashMap<String, Integer> sold, Set<String> received) {}

    /**
     * Movement since the shop's last finished count, so sales made after a count (e.g. late at night)
     * land on the next count instead of being skipped. If the last count was more than a day before,
     * the window starts at the beginning of the trading day.
     */
    private Movement movementSinceLastCount(String merchantId, LocalDate tradingDate) {
        LocalDateTime dayStart = tradingDate.atStartOfDay();
        LocalDateTime from = closeDayRepository
                .findTopByMerchantIdAndTradingDateBeforeAndClosedAtIsNotNullOrderByTradingDateDesc(merchantId, tradingDate)
                .map(CloseDay::getClosedAt)
                .filter(closedAt -> closedAt.isAfter(dayStart.minusDays(1)))
                .orElse(dayStart);
        LocalDateTime to = LocalDateTime.now().plusMinutes(1);

        LinkedHashMap<String, Integer> sold = new LinkedHashMap<>();
        for (Object[] row : saleTransactionRepository.sumQuantityByItemBetween(merchantId, from, to)) {
            sold.put((String) row[0], row[1] == null ? 0 : ((Number) row[1]).intValue());
        }
        Set<String> received = new LinkedHashSet<>(auditRepository.findItemCodesWithActionBetween(
                merchantId, List.of("RESTOCK"), from, to));
        for (InventoryTransaction t : transactionRepository
                .findByMerchantIdAndTransactionDateBetweenOrderByTransactionDateDesc(merchantId, from, to)) {
            received.add(t.getItemCode());
        }
        return new Movement(sold, received);
    }

    private static Map<String, Object> movedView(Inventory stock, String movement) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemName", stock.getItemName());
        m.put("itemCode", stock.getItemCode());
        m.put("movement", movement);
        return m;
    }

    /**
     * Current stock row per item code. inventory_stock can hold more than one row for the same
     * item code (daily rollover), so the most recent row wins.
     */
    public Map<String, Inventory> currentStockByItemCode(String merchantId) {
        Map<String, Inventory> byCode = new LinkedHashMap<>();
        for (Inventory item : inventoryRepository.findByMerchantId(merchantId)) {
            byCode.merge(item.getItemCode(), item, (a, b) -> isNewer(b, a) ? b : a);
        }
        return byCode;
    }

    private boolean isNewer(Inventory candidate, Inventory current) {
        LocalDate c = candidate.getRecordDate();
        LocalDate o = current.getRecordDate();
        if (c != null && o != null && !c.equals(o)) return c.isAfter(o);
        return candidate.getId() != null && current.getId() != null && candidate.getId() > current.getId();
    }

    private Map<String, LocalDateTime> lastCountedAtByItem(String merchantId) {
        Map<String, LocalDateTime> result = new HashMap<>();
        for (Object[] row : stockCountLineRepository.findLastCountedAtByItem(merchantId)) {
            result.put((String) row[0], (LocalDateTime) row[1]);
        }
        return result;
    }

    private StockCountLine buildLine(CloseDay closeDay, Inventory stock, CountTrigger trigger,
                                     int unitsSold, String reason) {
        return StockCountLine.builder()
                .closeDayId(closeDay.getId())
                .merchantId(closeDay.getMerchantId())
                .inventoryId(stock.getId())
                .itemCode(stock.getItemCode())
                .itemName(stock.getItemName())
                .unitsSold(unitsSold)
                .unitCost(stock.getUnitCost())
                .unitPrice(stock.getUnitPrice())
                .status(StockCountStatus.PENDING)
                .trigger(trigger)
                .queueReason(reason)
                .requestedBy("SYSTEM")
                .build();
    }
}
