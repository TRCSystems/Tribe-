package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryStockAudit;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryTransaction;
import com.dayworks_ltd.loyalty_engine.inventory.models.PriceChange;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryStockAuditRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.PriceChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Stock audit trail: how every item's quantity moved — stock added, sold, dispatched, corrected
 * after a stock count — per product and for one product in detail. Built from the stock audit rows
 * (sales, restocks, counts, imports…), the stock-transfer log (stock received from a distributor)
 * and the price change log (shown for context, no quantity).
 */
@Service
@RequiredArgsConstructor
public class StockAuditTrailService {

    /** What kind of movement, in the owner's words, and which summary column it counts towards. */
    public enum Kind {
        ADDED("Stock added"),
        SOLD("Sold"),
        DISPATCHED("Dispatched to shops"),
        COUNT_CORRECTION("Stock count correction"),
        CORRECTION("Other correction"),
        PRICE("Price change");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private static final int MAX_DAYS = 366;

    private final InventoryStockAuditRepository auditRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final PriceChangeRepository priceChangeRepository;
    private final StockCountQueueService queueService;
    private final UserRepository userRepository;

    public record Period(LocalDateTime from, LocalDateTime to) {}

    /** [from 00:00, to 23:59:59]; defaults to the last 30 days; at most a year. */
    public static Period period(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) throw new IllegalArgumentException("The start date is after the end date");
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) throw new IllegalArgumentException("Choose at most one year");
        return new Period(start.atStartOfDay(), end.plusDays(1).atStartOfDay().minusNanos(1));
    }

    /** One row per product: what was added, sold, dispatched and corrected in the period, plus stock now. */
    public Map<String, Object> summary(String merchantId, Period p) {
        Map<String, Inventory> stock = queueService.currentStockByItemCode(merchantId);
        Map<String, long[]> totals = new HashMap<>();   // per item: ADDED, SOLD, DISPATCHED, COUNT, OTHER, moves
        Map<String, LocalDateTime> last = new HashMap<>();

        for (InventoryStockAudit a : auditRepository.findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(merchantId, p.from(), p.to())) {
            int change = change(a);
            if (change == 0) continue;
            add(totals, a.getItemCode(), kindOf(a.getActionType(), change), change);
            last.merge(a.getItemCode(), at(a), (x, y) -> y.isAfter(x) ? y : x);
        }
        for (InventoryTransaction t : transactionRepository.findByMerchantIdAndTransactionDateBetweenOrderByTransactionDateDesc(merchantId, p.from(), p.to())) {
            int change = t.getQuantity() == null ? 0 : t.getQuantity();
            if (change == 0 || t.getItemCode() == null) continue;
            add(totals, t.getItemCode(), kindOf(t), change);
            last.merge(t.getItemCode(), t.getTransactionDate(), (x, y) -> y.isAfter(x) ? y : x);
        }

        Set<String> codes = new TreeSet<>(stock.keySet());
        codes.addAll(totals.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : codes) {
            Inventory item = stock.get(code);
            long[] t = totals.getOrDefault(code, new long[6]);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("itemCode", code);
            r.put("itemName", item == null ? code : item.getItemName());
            r.put("category", item == null ? null : item.getProductCategory());
            r.put("added", t[0]);
            r.put("sold", t[1]);
            r.put("dispatched", t[2]);
            r.put("countCorrections", t[3]);
            r.put("otherCorrections", t[4]);
            r.put("netChange", t[0] + t[1] + t[2] + t[3] + t[4]);
            r.put("movements", t[5]);
            r.put("currentStock", item == null ? null : item.getAvailableStock());
            r.put("lastMovementAt", last.get(code));
            rows.add(r);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> -((Number) r.get("movements")).longValue())
                .thenComparing(r -> String.valueOf(r.get("itemName")).toLowerCase()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", p.from().toLocalDate());
        out.put("to", p.to().toLocalDate());
        out.put("items", rows);
        return out;
    }

    /** Every movement of one product in the period, oldest first, with the stock before and after where known. */
    public Map<String, Object> itemTrail(String merchantId, String itemCode, Period p) {
        Inventory item = queueService.currentStockByItemCode(merchantId).get(itemCode);
        List<Map<String, Object>> events = new ArrayList<>();
        Set<Long> userIds = new HashSet<>();

        for (InventoryStockAudit a : auditRepository
                .findByMerchantIdAndItemCodeAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(merchantId, itemCode, p.from(), p.to())) {
            int change = change(a);
            if (change == 0) continue;
            Kind kind = kindOf(a.getActionType(), change);
            Map<String, Object> e = event(at(a), kind, describe(a.getActionType(), change), change,
                    a.getAvailableStockBefore(), a.getAvailableStockAfter(), readableReference(a), a.getNotes());
            e.put("by", a.getPerformedBy());
            if (a.getPerformedBy() != null && a.getPerformedBy().matches("\\d{1,18}") && !a.getPerformedBy().equals(merchantId)) {
                userIds.add(Long.valueOf(a.getPerformedBy()));
            }
            events.add(e);
        }
        for (InventoryTransaction t : transactionRepository
                .findByMerchantIdAndItemCodeAndTransactionDateBetweenOrderByTransactionDateAsc(merchantId, itemCode, p.from(), p.to())) {
            int change = t.getQuantity() == null ? 0 : t.getQuantity();
            if (change == 0) continue;
            Integer before = t.getPreviousAvailableStock();
            Map<String, Object> e = event(t.getTransactionDate(), kindOf(t), describe(t), change,
                    before, before == null ? null : before + change,
                    t.getTransactionCode() != null ? t.getTransactionCode() : t.getReferenceType(), t.getNotes());
            e.put("by", t.getPerformedByUserId() == null ? null : String.valueOf(t.getPerformedByUserId()));
            if (t.getPerformedByUserId() != null) userIds.add(t.getPerformedByUserId());
            events.add(e);
        }
        for (PriceChange c : priceChangeRepository
                .findByMerchantIdAndItemCodeAndChangedAtBetweenOrderByChangedAtAscIdAsc(merchantId, itemCode, p.from(), p.to())) {
            String what = switch (c.getField()) {
                case UNIT_COST -> "Unit cost";
                case UNIT_PRICE -> "Selling price";
                case WHOLESALE_PRICE -> "Wholesale price";
            };
            String note = what + ": " + (c.getOldValue() == null ? "none" : "KES " + c.getOldValue().stripTrailingZeros().toPlainString())
                    + " → " + (c.getNewValue() == null ? "removed" : "KES " + c.getNewValue().stripTrailingZeros().toPlainString());
            Map<String, Object> e = event(c.getChangedAt(), Kind.PRICE, what + " changed", null, null, null, null, note);
            e.put("by", c.getChangedByUserId() == null ? null : String.valueOf(c.getChangedByUserId()));
            if (c.getChangedByUserId() != null) userIds.add(c.getChangedByUserId());
            events.add(e);
        }

        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User u : userRepository.findAllById(userIds)) names.put(u.getUserId(), u.getUsername());
        }
        for (Map<String, Object> e : events) e.put("by", who((String) e.get("by"), merchantId, names));
        events.sort(Comparator.comparing((Map<String, Object> e) -> (LocalDateTime) e.get("at")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemCode", itemCode);
        out.put("itemName", item == null ? itemCode : item.getItemName());
        out.put("currentStock", item == null ? null : item.getAvailableStock());
        out.put("from", p.from().toLocalDate());
        out.put("to", p.to().toLocalDate());
        out.put("events", events);
        return out;
    }

    // ───────────────────────────── classification ─────────────────────────────

    static Kind kindOf(String actionType, int change) {
        if (actionType == null) return Kind.CORRECTION;
        return switch (actionType) {
            case "SALE" -> Kind.SOLD;
            case "WHOLESALESALE" -> Kind.DISPATCHED;
            case "RESTOCK", "INVOICE_RECEIPT", "NEW_ITEM", "ORDER_RECEIVED" -> Kind.ADDED;
            case "STOCK_COUNT_ADJUSTMENT" -> Kind.COUNT_CORRECTION;
            default -> Kind.CORRECTION; // IMPORT, MANUAL_CORRECTION, REVERSAL, anything new
        };
    }

    static Kind kindOf(InventoryTransaction t) {
        if (t.getTransactionType() == null) return Kind.CORRECTION;
        return switch (t.getTransactionType()) {
            case OPENING_BALANCE, PURCHASE_FROM_SUPPLIER, STOCK_TRANSFER_RECEIVED -> Kind.ADDED;
            case SALE -> Kind.SOLD;
            case STOCK_TRANSFER_ISSUED -> Kind.DISPATCHED;
            case STOCK_COUNT_ADJUSTMENT -> Kind.COUNT_CORRECTION;
            default -> Kind.CORRECTION;
        };
    }

    static String describe(String actionType, int change) {
        if (actionType == null) return "Stock changed";
        return switch (actionType) {
            case "SALE" -> "Sold at the till";
            case "WHOLESALESALE" -> "Dispatched to a shop (order)";
            case "RESTOCK" -> "Stock added";
            case "INVOICE_RECEIPT" -> "Received on supplier invoice";
            case "NEW_ITEM" -> "Opening stock (new item)";
            case "ORDER_RECEIVED" -> "Received from distributor order";
            case "STOCK_COUNT_ADJUSTMENT" -> change < 0 ? "Stock count: shortage written off" : "Stock count: extra stock added";
            case "IMPORT" -> "Stock set from imported file";
            case "MANUAL_CORRECTION" -> "Quantity corrected by an admin";
            case "REVERSAL" -> "Reversal";
            default -> actionType.replace('_', ' ').toLowerCase();
        };
    }

    private static String describe(InventoryTransaction t) {
        if (t.getTransactionType() == null) return "Stock changed";
        return switch (t.getTransactionType()) {
            case STOCK_TRANSFER_RECEIVED -> "Received from distributor";
            case STOCK_TRANSFER_ISSUED -> "Issued to a shop";
            case PURCHASE_FROM_SUPPLIER -> "Bought from supplier";
            case OPENING_BALANCE -> "Opening stock";
            case DAMAGE -> "Damaged";
            case LOSS -> "Lost";
            case RETURN_TO_SUPPLIER -> "Returned to supplier";
            default -> t.getTransactionType().name().replace('_', ' ').toLowerCase();
        };
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Units in (+) or out (−). Old sale rows may lack before/after stock, so sold units are used instead. */
    public static int change(InventoryStockAudit a) {
        if (a.getAvailableStockBefore() != null && a.getAvailableStockAfter() != null) {
            return a.getAvailableStockAfter() - a.getAvailableStockBefore();
        }
        if (a.getSoldStockBefore() != null && a.getSoldStockAfter() != null) {
            return -(a.getSoldStockAfter() - a.getSoldStockBefore());
        }
        if (a.getAddedStockBefore() != null && a.getAddedStockAfter() != null) {
            return a.getAddedStockAfter() - a.getAddedStockBefore();
        }
        return 0;
    }

    /** Internal ids mean nothing to an owner: drop the item's own id, name stock count lines. */
    static String readableReference(InventoryStockAudit a) {
        String ref = a.getReference();
        if (ref == null || ref.isBlank() || ref.equals("N/A")) return null;
        if (a.getInventoryId() != null && ref.equals(String.valueOf(a.getInventoryId()))) return null;
        if (ref.startsWith("STOCK_COUNT_LINE-")) return "Stock count review";
        return ref;
    }

    private static LocalDateTime at(InventoryStockAudit a) {
        return a.getCreatedAt() != null ? a.getCreatedAt() : a.getCreatedAtInternal();
    }

    private static void add(Map<String, long[]> totals, String code, Kind kind, int change) {
        long[] t = totals.computeIfAbsent(code, k -> new long[6]);
        switch (kind) {
            case ADDED -> t[0] += change;
            case SOLD -> t[1] += change;
            case DISPATCHED -> t[2] += change;
            case COUNT_CORRECTION -> t[3] += change;
            default -> t[4] += change;
        }
        t[5]++;
    }

    private static Map<String, Object> event(LocalDateTime at, Kind kind, String description, Integer change,
                                             Integer before, Integer after, String reference, String notes) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at);
        e.put("kind", kind.name());
        e.put("kindLabel", kind.label);
        e.put("description", description);
        e.put("change", change);
        e.put("stockBefore", before);
        e.put("stockAfter", after);
        e.put("reference", reference);
        e.put("notes", notes);
        return e;
    }

    /** "12" → username, the shop's own id → "Shop login", ADMIN/SYSTEM as is. */
    static String who(String performedBy, String merchantId, Map<Long, String> names) {
        if (performedBy == null || performedBy.isBlank()) return "—";
        if (performedBy.equals(merchantId)) return "Shop login";
        if (performedBy.matches("\\d{1,18}")) {
            String name = names.get(Long.valueOf(performedBy));
            return name != null ? name : "User " + performedBy;
        }
        return switch (performedBy) {
            case "ADMIN" -> "Admin";
            case "SYSTEM" -> "System";
            default -> performedBy;
        };
    }
}
