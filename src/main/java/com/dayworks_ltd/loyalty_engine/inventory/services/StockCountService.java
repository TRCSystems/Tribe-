package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.enums.CountTrigger;
import com.dayworks_ltd.loyalty_engine.inventory.enums.CloseDayStatus;
import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.enums.VarianceReason;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryStockAudit;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.CloseDayRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryStockAuditRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.SaleTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.StockCountLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * End-of-day blind stock count.
 *
 * Flow: start -> counter submits blind counts (one automatic blind recount on mismatch)
 *       -> finalize (manual, or the nightly job) -> daily discrepancy report.
 *
 * Variances are flagged and reported only. Stock figures change only if the owner
 * explicitly asks for an adjustment when reviewing a variance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCountService {

    private static final Set<StockCountStatus> UNCOUNTED =
            EnumSet.of(StockCountStatus.PENDING, StockCountStatus.RECOUNT_REQUESTED);
    private static final Set<StockCountStatus> FINAL_COUNTED =
            EnumSet.of(StockCountStatus.RECONCILED, StockCountStatus.VARIANCE_OPEN, StockCountStatus.CLASSIFIED);

    private final CloseDayRepository closeDayRepository;
    private final StockCountLineRepository lineRepository;
    private final StockCountQueueService queueService;
    private final InventoryRepository inventoryRepository;
    private final InventoryStockAuditRepository auditRepository;
    private final SaleTransactionRepository saleTransactionRepository;
    private final ReportRecipientService recipients;
    private final TradingDay tradingDay;

    /** A single shortage at or above this value (at cost) triggers an immediate SMS to the owner. */
    @Value("${stock-count.alert-threshold:1000}")
    private BigDecimal alertThreshold;

    /** Window for spotting items that go missing repeatedly. */
    @Value("${stock-count.repeat-window-days:7}")
    private int repeatWindowDays;

    /**
     * When true, the person who counted an item may not review its variance. Off for now:
     * shops share one login between owner and attendant, so the counter is often the reviewer.
     */
    @Value("${stock-count.block-self-review:false}")
    private boolean blockSelfReview;

    // ───────────────────────────── counting ─────────────────────────────

    /** Opens today's count session (building the queue on first call). Idempotent for the day. */
    @Transactional
    public CloseDay startCount(String merchantId, Long userId) {
        return startCount(merchantId, String.valueOf(userId));
    }

    @Transactional
    public CloseDay startCount(String merchantId, String initiatedBy) {
        LocalDate today = tradingDay.today();
        CloseDay closeDay = closeDayRepository.findByMerchantIdAndTradingDate(merchantId, today)
                .orElse(null);

        if (closeDay != null) {
            if (closeDay.isClosed() && !reopenIfNothingCounted(closeDay, initiatedBy)) {
                throw new IllegalStateException("Stock count for " + today + " has already been finalized");
            }
            return addMissingItems(closeDay);
        }

        closeDay = closeDayRepository.save(CloseDay.builder()
                .merchantId(merchantId)
                .tradingDate(today)
                .status(CloseDayStatus.COUNTING)
                .closeInitiatedAt(LocalDateTime.now())
                .closeInitiatedBy(initiatedBy)
                .build());

        List<StockCountLine> lines = queueService.buildQueueForCloseDay(closeDay);
        closeDay.setTotalSkusMoved((int) lines.stream().filter(l -> l.getUnitsSold() != null && l.getUnitsSold() > 0).count());
        closeDay.setTotalSkusQueued(lines.size());
        closeDay.setTotalSkusCounted(0);
        return closeDayRepository.save(closeDay);
    }

    /**
     * A count closed without a single item counted (e.g. auto-finalized at 23:30 before anyone started) is
     * reopened so the shop can still count tonight. Returns false when something was counted.
     */
    private boolean reopenIfNothingCounted(CloseDay closeDay, String reopenedBy) {
        List<StockCountLine> lines = getLines(closeDay);
        boolean anyCounted = lines.stream().anyMatch(l -> l.getStatus() != StockCountStatus.NOT_COUNTED
                || l.getFirstCount() != null || l.getPhysicalQuantity() != null);
        if (anyCounted) return false;

        for (StockCountLine line : lines) {
            line.setStatus(StockCountStatus.PENDING);
            line.setExpectedQuantity(null);
            lineRepository.save(line);
        }
        closeDay.setStatus(CloseDayStatus.COUNTING);
        closeDay.setClosedAt(null);
        closeDay.setClosedBy(null);
        closeDay.setCloseNote(appendNote(closeDay.getCloseNote(), "Reopened by " + reopenedBy + " (nothing had been counted)"));
        refreshTotals(closeDay);
        closeDayRepository.save(closeDay);
        log.info("Stock count {} for merchant {} reopened by {}: nothing had been counted",
                closeDay.getTradingDate(), closeDay.getMerchantId(), reopenedBy);
        return true;
    }

    /**
     * Keeps an open count complete: adds items sold or added to stock since the list was made, and
     * fills in unmoved items on lists made before every item was included. No-op for finished counts.
     */
    @Transactional
    public CloseDay addMissingItems(CloseDay closeDay) {
        if (closeDay.isClosed()) return closeDay;
        List<StockCountLine> existing = getLines(closeDay);
        int added = queueService.addMissingItems(closeDay, existing);
        if (added == 0) return closeDay;
        closeDay.setTotalSkusQueued(existing.size() + added);
        return closeDayRepository.save(closeDay);
    }

    /**
     * 9 PM job body: prepares tonight's count list (if not started yet) and texts every number
     * on the shop's list how many items to count. Returns false when the count is already finished.
     */
    @Transactional
    public int prepareCountAndRemind(String merchantId) {
        Optional<CloseDay> existing = closeDayRepository.findByMerchantIdAndTradingDate(merchantId, tradingDay.today());
        if (existing.isPresent() && existing.get().isClosed()) return -1;

        CloseDay closeDay = existing.orElseGet(() -> startCount(merchantId, "SYSTEM"));
        List<StockCountLine> lines = getLines(closeDay);
        long remaining = lines.stream().filter(l -> UNCOUNTED.contains(l.getStatus())).count();
        long moved = lines.stream()
                .filter(l -> UNCOUNTED.contains(l.getStatus()) && l.getTrigger() == CountTrigger.MOVEMENT_BASED)
                .count();
        if (remaining == 0) return 0;

        recipients.send(merchantId, ReportRecipientService.ReportType.REMINDER,
                "TRIBE " + smsSafe(recipients.shopName(merchantId)) + ": time for tonight's stock count. "
                        + remaining + " item" + (remaining == 1 ? "" : "s") + " to count"
                        + (moved > 0 ? " (" + moved + " moved today - count those first)" : "")
                        + ". Open TRIBE > Inventory Management > Stock Count.");
        return (int) remaining;
    }

    /**
     * Shops that get the 9 PM reminder: anyone who sold or received stock today, or who has a
     * count open for today. (Only "sold today" was too narrow — a shop with an open count got nothing.)
     */
    public List<String> merchantsToRemind(LocalDate date) {
        Set<String> ids = new LinkedHashSet<>(saleTransactionRepository.findMerchantIdsWithSalesOn(date));
        ids.addAll(auditRepository.findMerchantIdsWithActionBetween(
                List.of("RESTOCK"), date.atStartOfDay(), date.plusDays(1).atStartOfDay()));
        findOpenSessions(date).forEach(d -> ids.add(d.getMerchantId()));
        return new ArrayList<>(ids);
    }

    /** Items that moved today, shown on the counting screen before the count starts (no quantities). */
    public List<Map<String, Object>> movedToday(String merchantId) {
        return queueService.movedItems(merchantId, tradingDay.today());
    }

    /** The business day for counts (before 05:00 still counts as yesterday). */
    public LocalDate today() {
        return tradingDay.today();
    }

    public Optional<CloseDay> findSession(String merchantId, LocalDate date) {
        return closeDayRepository.findByMerchantIdAndTradingDate(merchantId, date);
    }

    public List<StockCountLine> getLines(CloseDay closeDay) {
        return lineRepository.findByCloseDayIdOrderByPriorityRankAsc(closeDay.getId());
    }

    /**
     * Records one blind count. The system quantity is read at this moment, so sales made
     * earlier in the day are already accounted for.
     *
     * First mismatch -> RECOUNT_REQUESTED (counter is not told the expected figure).
     * Recount        -> final; any remaining difference is flagged VARIANCE_OPEN.
     */
    @Transactional
    public StockCountLine submitCount(String merchantId, Long userId, Long lineId, int countedQuantity) {
        return submitCount(merchantId, String.valueOf(userId), lineId, countedQuantity);
    }

    /** Same, with any counter label (e.g. "WhatsApp 0712345678" for counts sent on WhatsApp). */
    @Transactional
    public StockCountLine submitCount(String merchantId, String countedBy, Long lineId, int countedQuantity) {
        if (countedQuantity < 0) {
            throw new IllegalArgumentException("Counted quantity cannot be negative");
        }
        StockCountLine line = getOwnedLine(merchantId, lineId);
        CloseDay closeDay = closeDayRepository.findById(line.getCloseDayId())
                .orElseThrow(() -> new IllegalStateException("Count session not found"));
        if (closeDay.isClosed()) {
            throw new IllegalStateException("This stock count has already been finalized");
        }
        if (!UNCOUNTED.contains(line.getStatus())) {
            throw new IllegalStateException(line.getItemName() + " has already been counted");
        }

        int expected = currentSystemQuantity(line);
        boolean firstAttempt = line.getStatus() == StockCountStatus.PENDING;

        line.setExpectedQuantity(expected);
        line.setCountedBy(countedBy);
        line.setCountedAt(LocalDateTime.now());

        if (firstAttempt) {
            line.setFirstCount(countedQuantity);
            if (countedQuantity != expected) {
                line.setStatus(StockCountStatus.RECOUNT_REQUESTED);
                return lineRepository.save(line);
            }
        }

        applyFinalCount(line, countedQuantity);
        StockCountLine saved = lineRepository.save(line);
        refreshTotals(closeDay);

        if (saved.getStatus() == StockCountStatus.VARIANCE_OPEN && Boolean.TRUE.equals(saved.getMaterialVariance())) {
            recipients.sendStockReport(merchantId, ReportRecipientService.ReportType.ALERT, "STOCK ALERT: " + smsSafe(saved.getItemName()) + " is short by "
                    + Math.abs(saved.getVarianceQuantity()) + " (KES " + saved.getVarianceValue().abs()
                    + " at cost). Counted " + saved.getPhysicalQuantity() + ", system " + saved.getExpectedQuantity() + ".");
        }
        return saved;
    }

    // ───────────────────────────── finalize ─────────────────────────────

    /**
     * Closes the count. Items never counted are flagged NOT_COUNTED; an item stuck on
     * RECOUNT_REQUESTED keeps its first count as the final figure.
     */
    @Transactional
    public CloseDay finalizeCount(String merchantId, String finalizedBy, String note) {
        CloseDay closeDay = closeDayRepository.findByMerchantIdAndTradingDate(merchantId, tradingDay.today())
                .orElseThrow(() -> new IllegalStateException("No stock count has been started today"));
        return finalizeSession(closeDay, finalizedBy, note);
    }

    @Transactional
    public CloseDay finalizeSession(CloseDay closeDay, String finalizedBy, String note) {
        if (closeDay.isClosed()) {
            throw new IllegalStateException("Stock count for " + closeDay.getTradingDate() + " is already finalized");
        }

        for (StockCountLine line : lineRepository.findByCloseDayIdAndStatusIn(closeDay.getId(), UNCOUNTED)) {
            int expected = currentSystemQuantity(line);
            line.setExpectedQuantity(expected);
            if (line.getStatus() == StockCountStatus.RECOUNT_REQUESTED && line.getFirstCount() != null) {
                applyFinalCount(line, line.getFirstCount());
                line.setVarianceNote(appendNote(line.getVarianceNote(), "Recount not done; first count used"));
            } else {
                line.setStatus(StockCountStatus.NOT_COUNTED);
            }
            lineRepository.save(line);
        }

        refreshTotals(closeDay);
        boolean exceptions = closeDay.getTotalVariances() > 0 || closeDay.getTotalSkusNotCounted() > 0;
        closeDay.setStatus(exceptions ? CloseDayStatus.CLOSED_WITH_EXCEPTION : CloseDayStatus.CLOSED);
        closeDay.setClosedAt(LocalDateTime.now());
        closeDay.setClosedBy(finalizedBy);
        closeDay.setCloseNote(appendNote(closeDay.getCloseNote(), note));
        CloseDay saved = closeDayRepository.save(closeDay);

        recipients.sendStockReport(saved.getMerchantId(), ReportRecipientService.ReportType.SUMMARY,
                buildSummarySms(saved, getLines(saved)));
        return saved;
    }

    /** Sessions still open on the date — finalized one by one by the nightly job. */
    public List<CloseDay> findOpenSessions(LocalDate date) {
        return closeDayRepository.findByTradingDateAndStatusIn(
                date, EnumSet.of(CloseDayStatus.OPEN, CloseDayStatus.COUNTING));
    }

    /** Merchants that sold something on the date but never started a count. */
    public List<String> findMerchantsThatSkippedCount(LocalDate date) {
        return saleTransactionRepository.findMerchantIdsWithSalesOn(date).stream()
                .filter(id -> closeDayRepository.findByMerchantIdAndTradingDate(id, date).isEmpty())
                .toList();
    }

    /** Records a skipped count: the queue is built and every line is reported NOT_COUNTED. */
    @Transactional
    public CloseDay recordSkippedCount(String merchantId, LocalDate date) {
        CloseDay skipped = closeDayRepository.save(CloseDay.builder()
                .merchantId(merchantId)
                .tradingDate(date)
                .status(CloseDayStatus.COUNTING)
                .closeInitiatedAt(LocalDateTime.now())
                .closeInitiatedBy("SYSTEM")
                .build());
        queueService.buildQueueForCloseDay(skipped);
        return finalizeSession(skipped, "SYSTEM", "No stock count was done today");
    }

    // ───────────────────────────── review ─────────────────────────────

    /**
     * Owner/admin records why a variance happened (the counter may review it unless
     * stock-count.block-self-review is on). Stock is only adjusted when adjustStock is true.
     */
    @Transactional
    public StockCountLine classifyVariance(String merchantId, Long reviewerUserId, Long lineId,
                                           VarianceReason reason, String note, boolean adjustStock) {
        StockCountLine line = getOwnedLine(merchantId, lineId);
        if (line.getStatus() != StockCountStatus.VARIANCE_OPEN && line.getStatus() != StockCountStatus.CLASSIFIED) {
            throw new IllegalStateException("Only lines with a variance can be classified");
        }
        if (blockSelfReview && String.valueOf(reviewerUserId).equals(line.getCountedBy())) {
            throw new IllegalStateException("The person who counted an item cannot review its variance");
        }
        if (reason == null) {
            throw new IllegalArgumentException("A variance reason is required");
        }

        line.setVarianceReason(reason);
        line.setVarianceNote(appendNote(line.getVarianceNote(), note));
        line.setClassifiedBy(String.valueOf(reviewerUserId));
        line.setClassifiedAt(LocalDateTime.now());
        line.setStatus(StockCountStatus.CLASSIFIED);

        if (adjustStock && !Boolean.TRUE.equals(line.getStockAdjusted()) && line.getVarianceQuantity() != 0) {
            adjustStockForVariance(line, reviewerUserId);
            line.setStockAdjusted(true);
        }

        StockCountLine saved = lineRepository.save(line);
        closeDayRepository.findById(line.getCloseDayId()).ifPresent(this::refreshTotals);
        return saved;
    }

    // ───────────────────────────── reporting ─────────────────────────────

    /** Full discrepancy report for one day — owner/admin only. */
    /** Keys removed from the report when the viewer may not see stock quantities. */
    private static final List<String> QUANTITY_KEYS = List.of(
            "expectedQuantity", "firstCount", "countedQuantity", "varianceQuantity", "unitsSoldToday", "unitsMissing");

    /**
     * @param showQuantities false for anyone at the shop who hasn't unlocked with the owner's code:
     *                       they see which items are missing / extra and the value, never the figures.
     */
    public Map<String, Object> buildReport(CloseDay closeDay, boolean showQuantities) {
        List<StockCountLine> lines = getLines(closeDay);

        Comparator<StockCountLine> worstFirst = Comparator
                .comparing((StockCountLine l) -> reportGroup(l.getStatus()))
                .thenComparing(l -> l.getVarianceValue() == null ? BigDecimal.ZERO : l.getVarianceValue());

        List<Map<String, Object>> discrepancies = lines.stream()
                .filter(l -> l.getStatus() == StockCountStatus.VARIANCE_OPEN
                        || l.getStatus() == StockCountStatus.CLASSIFIED
                        || l.getStatus() == StockCountStatus.NOT_COUNTED
                        || l.getStatus() == StockCountStatus.RECOUNT_REQUESTED)
                .sorted(worstFirst)
                .map(this::fullLineView)
                .toList();

        List<Map<String, Object>> allLines = lines.stream().map(this::fullLineView).toList();
        List<Map<String, Object>> repeats = repeatShortages(closeDay.getMerchantId());
        if (!showQuantities) {
            discrepancies = discrepancies.stream().map(StockCountService::withoutQuantities).toList();
            allLines = allLines.stream().map(StockCountService::withoutQuantities).toList();
            repeats = repeats.stream().map(StockCountService::withoutQuantities).toList();
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summaryView(closeDay));
        report.put("quantitiesHidden", !showQuantities);
        report.put("discrepancies", discrepancies);
        report.put("repeatShortages", repeats);
        report.put("allLines", allLines);
        return report;
    }

    public List<Map<String, Object>> history(String merchantId, LocalDate from, LocalDate to) {
        return closeDayRepository.findByMerchantIdAndTradingDateBetweenOrderByTradingDateDesc(merchantId, from, to)
                .stream().map(this::summaryView).toList();
    }

    /** What a counter (SALES_PERSON) may see — no system quantities, no variance. */
    public Map<String, Object> blindLineView(StockCountLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.getId());
        m.put("rank", l.getPriorityRank());
        m.put("itemName", l.getItemName());
        m.put("itemCode", l.getItemCode());
        // Why it is on the list — no quantities: SOLD / RECEIVED today, or SPOT_CHECK for slow movers
        m.put("movement", movement(l));
        m.put("status", switch (l.getStatus()) {
            case PENDING -> "TO_COUNT";
            case RECOUNT_REQUESTED -> "RECOUNT";
            case NOT_COUNTED -> "NOT_COUNTED";
            default -> "DONE";
        });
        return m;
    }

    public Map<String, Object> fullLineView(StockCountLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.getId());
        m.put("rank", l.getPriorityRank());
        m.put("itemName", l.getItemName());
        m.put("itemCode", l.getItemCode());
        m.put("trigger", l.getTrigger());
        m.put("queueReason", l.getQueueReason());
        m.put("unitsSoldToday", l.getUnitsSold());
        m.put("expectedQuantity", l.getExpectedQuantity());
        m.put("firstCount", l.getFirstCount());
        m.put("countedTwice", countedTwice(l));
        m.put("outcome", l.getStatus() == StockCountStatus.NOT_COUNTED ? "NOT_COUNTED"
                : l.getVarianceQuantity() == null ? "PENDING"
                : l.getVarianceQuantity() < 0 ? "MISSING"
                : l.getVarianceQuantity() > 0 ? "EXTRA" : "MATCH");
        m.put("countedQuantity", l.getPhysicalQuantity());
        m.put("varianceQuantity", l.getVarianceQuantity());
        m.put("unitCost", l.getUnitCost());
        m.put("varianceValue", l.getVarianceValue());
        m.put("materialVariance", l.getMaterialVariance());
        m.put("status", l.getStatus());
        m.put("countedBy", l.getCountedBy());
        m.put("countedAt", l.getCountedAt());
        m.put("varianceReason", l.getVarianceReason());
        m.put("varianceNote", l.getVarianceNote());
        m.put("classifiedBy", l.getClassifiedBy());
        m.put("stockAdjusted", Boolean.TRUE.equals(l.getStockAdjusted()));
        return m;
    }

    public Map<String, Object> summaryView(CloseDay d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("closeDayId", d.getId());
        m.put("tradingDate", d.getTradingDate());
        m.put("status", d.getStatus());
        m.put("itemsMoved", d.getTotalSkusMoved());
        m.put("itemsQueued", d.getTotalSkusQueued());
        m.put("itemsCounted", d.getTotalSkusCounted());
        m.put("itemsNotCounted", d.getTotalSkusNotCounted());
        m.put("variances", d.getTotalVariances());
        m.put("openVariances", d.getOpenVariances());
        m.put("shortageValue", d.getShortageValue());
        m.put("overageValue", d.getOverageValue());
        m.put("netVarianceValue", d.getTotalVarianceValue());
        m.put("startedBy", d.getCloseInitiatedBy());
        m.put("closedAt", d.getClosedAt());
        m.put("closedBy", d.getClosedBy());
        m.put("note", d.getCloseNote());
        return m;
    }

    // ───────────────────────────── internals ─────────────────────────────

    private StockCountLine getOwnedLine(String merchantId, Long lineId) {
        StockCountLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new IllegalArgumentException("Count line not found"));
        if (!merchantId.equals(line.getMerchantId())) {
            throw new IllegalArgumentException("Count line not found");
        }
        return line;
    }

    private int currentSystemQuantity(StockCountLine line) {
        Inventory stock = line.getInventoryId() == null ? null
                : inventoryRepository.findById(line.getInventoryId()).orElse(null);
        if (stock == null) {
            stock = queueService.currentStockByItemCode(line.getMerchantId()).get(line.getItemCode());
        }
        return stock == null || stock.getAvailableStock() == null ? 0 : stock.getAvailableStock();
    }

    private void applyFinalCount(StockCountLine line, int physical) {
        int variance = physical - line.getExpectedQuantity();
        BigDecimal cost = line.getUnitCost() == null ? BigDecimal.ZERO : line.getUnitCost();
        BigDecimal value = cost.multiply(BigDecimal.valueOf(variance)).setScale(2, RoundingMode.HALF_UP);

        line.setPhysicalQuantity(physical);
        line.setVarianceQuantity(variance);
        line.setVarianceValue(value);
        line.setStatus(variance == 0 ? StockCountStatus.RECONCILED : StockCountStatus.VARIANCE_OPEN);
        line.setMaterialVariance(variance < 0 && value.abs().compareTo(alertThreshold) >= 0);
    }

    private void refreshTotals(CloseDay closeDay) {
        List<StockCountLine> lines = getLines(closeDay);
        int counted = 0, notCounted = 0, variances = 0, open = 0;
        BigDecimal shortage = BigDecimal.ZERO, overage = BigDecimal.ZERO;

        for (StockCountLine l : lines) {
            if (FINAL_COUNTED.contains(l.getStatus())) counted++;
            if (l.getStatus() == StockCountStatus.NOT_COUNTED) notCounted++;
            if (l.getStatus() == StockCountStatus.VARIANCE_OPEN) open++;
            if (l.getVarianceQuantity() != null && l.getVarianceQuantity() != 0) {
                variances++;
                BigDecimal v = l.getVarianceValue() == null ? BigDecimal.ZERO : l.getVarianceValue();
                if (v.signum() < 0) shortage = shortage.add(v.negate());
                else overage = overage.add(v);
            }
        }

        closeDay.setTotalSkusQueued(lines.size());
        closeDay.setTotalSkusCounted(counted);
        closeDay.setTotalSkusNotCounted(notCounted);
        closeDay.setTotalVariances(variances);
        closeDay.setOpenVariances(open);
        closeDay.setShortageValue(shortage);
        closeDay.setOverageValue(overage);
        closeDay.setTotalVarianceValue(overage.subtract(shortage));
        closeDay.setUpdatedAt(LocalDateTime.now());
        closeDayRepository.save(closeDay);
    }

    private static Map<String, Object> withoutQuantities(Map<String, Object> view) {
        Map<String, Object> copy = new LinkedHashMap<>(view);
        QUANTITY_KEYS.forEach(copy::remove);
        return copy;
    }

    /** Items short on 2+ days within the window — the pattern that points to theft rather than miscounts. */
    private List<Map<String, Object>> repeatShortages(String merchantId) {
        LocalDateTime since = LocalDate.now().minusDays(repeatWindowDays).atStartOfDay();
        Map<String, List<StockCountLine>> byItem = lineRepository.findShortagesSince(merchantId, since)
                .stream().collect(Collectors.groupingBy(StockCountLine::getItemCode));

        return byItem.values().stream()
                .filter(ls -> ls.stream().map(l -> l.getCountedAt().toLocalDate()).distinct().count() >= 2)
                .map(ls -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemCode", ls.get(0).getItemCode());
                    m.put("itemName", ls.get(0).getItemName());
                    m.put("daysShort", ls.stream().map(l -> l.getCountedAt().toLocalDate()).distinct().count());
                    m.put("unitsMissing", ls.stream().mapToInt(l -> -l.getVarianceQuantity()).sum());
                    m.put("valueMissing", ls.stream()
                            .map(l -> l.getVarianceValue() == null ? BigDecimal.ZERO : l.getVarianceValue().negate())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    m.put("countedBy", ls.stream().map(StockCountLine::getCountedBy).distinct().toList());
                    return m;
                })
                .sorted(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("valueMissing")).reversed())
                .toList();
    }

    /**
     * Writes the variance into the stock figures. startingStock moves with availableStock so that
     * computeAvailableStock() (starting + added - sold) does not undo the adjustment later.
     */
    private void adjustStockForVariance(StockCountLine line, Long reviewerUserId) {
        Inventory stock = inventoryRepository.findById(line.getInventoryId())
                .orElseThrow(() -> new IllegalStateException("Inventory item no longer exists"));
        int delta = line.getVarianceQuantity();
        int before = stock.getAvailableStock() == null ? 0 : stock.getAvailableStock();
        int after = Math.max(0, before + delta);

        stock.setAvailableStock(after);
        stock.setClosingStock(after);
        stock.syncTotals();
        stock.setLastUpdated(LocalDateTime.now());
        inventoryRepository.save(stock);

        auditRepository.save(InventoryStockAudit.builder()
                .inventoryId(stock.getId())
                .itemCode(stock.getItemCode())
                .merchantId(stock.getMerchantId())
                .actionType("STOCK_COUNT_ADJUSTMENT")
                .availableStockBefore(before)
                .availableStockAfter(after)
                .reference("STOCK_COUNT_LINE-" + line.getId())
                .performedBy(String.valueOf(reviewerUserId))
                .notes(line.getVarianceReason() + (line.getVarianceNote() != null ? ": " + line.getVarianceNote() : ""))
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static final int SMS_ITEMS_PER_SECTION = 8;

    /**
     * End-of-day SMS to the owner and report recipients: totals plus the actual problem items,
     * so the owner does not need to open the app to know what went missing.
     */
    static String buildSummarySms(CloseDay d, List<StockCountLine> lines) {
        StringBuilder sb = new StringBuilder("Stock count ").append(d.getTradingDate()).append(": ")
                .append(d.getTotalSkusCounted()).append("/").append(d.getTotalSkusQueued()).append(" items counted.");

        List<StockCountLine> missing = lines.stream()
                .filter(l -> l.getVarianceQuantity() != null && l.getVarianceQuantity() < 0)
                .sorted(Comparator.comparing(l -> l.getVarianceValue() == null ? BigDecimal.ZERO : l.getVarianceValue()))
                .toList();
        List<StockCountLine> extra = lines.stream()
                .filter(l -> l.getVarianceQuantity() != null && l.getVarianceQuantity() > 0)
                .toList();
        List<StockCountLine> notCounted = lines.stream()
                .filter(l -> l.getStatus() == StockCountStatus.NOT_COUNTED)
                .toList();

        if (missing.isEmpty() && extra.isEmpty()) {
            sb.append(" No discrepancies.");
        }
        if (!missing.isEmpty()) {
            sb.append(" MISSING (KES ").append(d.getShortageValue()).append(" at cost): ");
            appendItems(sb, missing, l -> smsSafe(l.getItemName()) + " " + l.getVarianceQuantity()
                    + " (sys " + l.getExpectedQuantity() + ", counted " + l.getPhysicalQuantity()
                    + (countedTwice(l) ? ", counted twice" : "") + ")");
        }
        if (!extra.isEmpty()) {
            sb.append(" EXTRA: ");
            appendItems(sb, extra, l -> smsSafe(l.getItemName()) + " +" + l.getVarianceQuantity()
                    + (countedTwice(l) ? " (counted twice)" : ""));
        }
        if (!notCounted.isEmpty()) {
            sb.append(" NOT COUNTED: ");
            appendItems(sb, notCounted, l -> smsSafe(l.getItemName()));
        }
        if (d.getCloseNote() != null && !d.getCloseNote().isBlank()) {
            sb.append(" Note: ").append(smsSafe(d.getCloseNote()));
        }
        return sb.toString();
    }

    private static void appendItems(StringBuilder sb, List<StockCountLine> items,
                                    java.util.function.Function<StockCountLine, String> format) {
        sb.append(items.stream().limit(SMS_ITEMS_PER_SECTION).map(format).collect(Collectors.joining("; ")));
        if (items.size() > SMS_ITEMS_PER_SECTION) {
            sb.append("; +").append(items.size() - SMS_ITEMS_PER_SECTION).append(" more in the app");
        }
        sb.append(".");
    }

    /** The SMS client builds JSON by hand, so strip characters that would break it. */
    private static String smsSafe(String text) {
        return text == null ? "" : text.replaceAll("[\\\\\"\\r\\n\\t]", " ").replaceAll(" {2,}", " ").trim();
    }

    /**
     * True when the counter had to count the item a second time: the first blind count did not
     * match the system, and the recount was submitted (either fixing it or confirming the gap).
     */
    static boolean countedTwice(StockCountLine l) {
        if (l.getFirstCount() == null || l.getPhysicalQuantity() == null) return false;
        if (l.getVarianceNote() != null && l.getVarianceNote().contains("Recount not done")) return false;
        boolean finalDiffers = l.getVarianceQuantity() != null && l.getVarianceQuantity() != 0;
        return finalDiffers || !l.getFirstCount().equals(l.getPhysicalQuantity());
    }

    private static String movement(StockCountLine l) {
        if (l.getTrigger() != CountTrigger.MOVEMENT_BASED) return "SPOT_CHECK";
        return l.getUnitsSold() != null && l.getUnitsSold() > 0 ? "SOLD" : "RECEIVED";
    }

    private static int reportGroup(StockCountStatus s) {
        return switch (s) {
            case VARIANCE_OPEN -> 0;
            case CLASSIFIED -> 1;
            case RECOUNT_REQUESTED -> 2;
            case NOT_COUNTED -> 3;
            default -> 4;
        };
    }

    private static String appendNote(String existing, String add) {
        if (add == null || add.isBlank()) return existing;
        return existing == null || existing.isBlank() ? add : existing + " | " + add;
    }
}
