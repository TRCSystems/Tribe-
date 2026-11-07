package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockReceipt;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.StockReceiptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Stock received from suppliers, sent as receipt photos on WhatsApp. The read receipt is held until the shop's
 * approver (reports.sms-approval.phone, else the registered phone) says yes; then each line is added to stock,
 * the item's cost price is set from the receipt, and products not in the list yet are created (without a selling price).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReceiptService {

    /** One receipt line as confirmed by the staff member. isNew = create the product. */
    public record Item(String itemCode, String itemName, int units, BigDecimal unitCost, boolean isNew) {}

    /** What approving a receipt did. */
    public record Applied(int items, int units, List<String> newItems) {}

    private static final TypeReference<List<Item>> ITEMS = new TypeReference<>() {};

    private final StockReceiptRepository receipts;
    private final InventoryRepository inventoryRepository;
    private final StockAuditRecorder stockAudit;
    private final ReportRecipientService recipients;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Who approves this shop's receipts (07…), or null when none can be found. */
    public String approverFor(String merchantId) {
        String approver = recipients.approverFor(merchantId);
        return approver != null ? approver : recipients.registeredPhoneOf(merchantId);
    }

    /** Saves the receipt for approval. */
    @Transactional
    public StockReceipt submit(String merchantId, String rawSubmitter, String supplier, String receiptNumber, String receiptDate,
                               BigDecimal total, String photoPath, List<Item> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("There are no products to add");
        if (items.stream().anyMatch(i -> i.units() <= 0)) throw new IllegalArgumentException("Every line needs a quantity");
        String approver = approverFor(merchantId);
        if (approver == null) throw new IllegalStateException("This shop has no phone number to approve stock");
        try {
            StockReceipt saved = receipts.save(StockReceipt.builder()
                    .merchantId(merchantId)
                    .submittedBy(ReportRecipientService.normalizePhone(rawSubmitter))
                    .approverPhone(approver)
                    .supplierName(clip(supplier, 200))
                    .receiptNumber(clip(receiptNumber, 100))
                    .receiptDate(clip(receiptDate, 20))
                    .total(total)
                    .photoPath(clip(photoPath, 250))
                    .linesJson(mapper.writeValueAsString(items))
                    .build());
            log.info("Stock receipt #{} for merchant {} from {} held for approval by {} ({} lines)",
                    saved.getId(), merchantId, saved.getSubmittedBy(), approver, items.size());
            return saved;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not save the receipt", e);
        }
    }

    /** Receipts with this number already sent for the shop (waiting or added), to warn about adding stock twice. */
    public List<StockReceipt> earlierWithNumber(String merchantId, String receiptNumber) {
        if (receiptNumber == null || receiptNumber.isBlank()) return List.of();
        return receipts.findByMerchantIdAndReceiptNumberAndStatusIn(merchantId, receiptNumber.trim(),
                EnumSet.of(StockReceipt.Status.PENDING, StockReceipt.Status.APPROVED));
    }

    public Optional<StockReceipt> find(Long id) {
        return receipts.findById(id);
    }

    public List<StockReceipt> pendingFor(String rawPhone) {
        try {
            return receipts.findByApproverPhoneAndStatusOrderByCreatedAtAsc(
                    ReportRecipientService.normalizePhone(rawPhone), StockReceipt.Status.PENDING);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    public void markShown(Long id, boolean shown) {
        receipts.findById(id).ifPresent(r -> {
            r.setShownOnWhatsApp(shown);
            receipts.save(r);
        });
    }

    public List<Item> items(StockReceipt r) {
        try {
            return mapper.readValue(r.getLinesJson(), ITEMS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Receipt #" + r.getId() + " lines can't be read", e);
        }
    }

    /**
     * Approves (adds the stock) or rejects a receipt. Only its approver can decide, and only once.
     * Empty when it isn't theirs or was already decided.
     */
    @Transactional
    public Optional<Applied> decide(Long id, String rawPhone, boolean approve) {
        String phone;
        try {
            phone = ReportRecipientService.normalizePhone(rawPhone);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<StockReceipt> found = receipts.findById(id)
                .filter(r -> r.getApproverPhone().equals(phone) && r.getStatus() == StockReceipt.Status.PENDING);
        if (found.isEmpty()) return Optional.empty();
        StockReceipt r = found.get();
        r.setStatus(approve ? StockReceipt.Status.APPROVED : StockReceipt.Status.REJECTED);
        r.setDecidedAt(LocalDateTime.now());
        r.setDecidedBy("WhatsApp " + phone);
        receipts.save(r);
        Applied applied = approve ? apply(r) : new Applied(0, 0, List.of());
        log.info("Stock receipt #{} for merchant {} {} by {}", id, r.getMerchantId(),
                approve ? "approved: " + applied.units() + " units on " + applied.items() + " items" : "rejected", phone);
        return Optional.of(applied);
    }

    private Applied apply(StockReceipt r) {
        String reference = "Receipt" + (r.getReceiptNumber() == null ? " #" + r.getId() : " " + r.getReceiptNumber());
        String by = "WhatsApp " + r.getSubmittedBy() + ", approved by " + r.getApproverPhone();
        String notes = "Supplier receipt sent on WhatsApp" + (r.getSupplierName() == null ? "" : " from " + r.getSupplierName());
        LocalDate purchased = parseDate(r.getReceiptDate());
        int items = 0, units = 0;
        List<String> created = new ArrayList<>();

        for (Item item : items(r)) {
            Inventory stock = null;
            if (item.itemCode() != null) {
                stock = inventoryRepository.findByMerchantIdAndItemCode(r.getMerchantId(), item.itemCode()).orElse(null);
            }
            if (stock == null) {
                stock = inventoryRepository.findByMerchantIdAndItemName(r.getMerchantId(), item.itemName())
                        .or(() -> inventoryRepository.findByMerchantIdAndItemCode(r.getMerchantId(), newCode(r.getMerchantId(), item.itemName())))
                        .orElse(null);
            }

            if (stock == null) {
                Inventory fresh = new Inventory();
                fresh.setMerchantId(r.getMerchantId());
                fresh.setItemName(item.itemName());
                fresh.setItemCode(newCode(r.getMerchantId(), item.itemName()));
                fresh.setRecordDate(LocalDate.now());
                fresh.setIsActive(true);
                fresh.setReorderLevel(0);
                fresh.addStock(item.units());
                fill(fresh, item, r, purchased);
                Inventory saved = inventoryRepository.save(fresh);
                stockAudit.record(saved, StockAuditRecorder.NEW_ITEM, 0, saved.getAvailableStock(), reference, by,
                        "New item from supplier receipt, needs a selling price. " + notes);
                created.add(saved.getItemName());
            } else {
                rollOverDay(stock);
                int before = stock.getAvailableStock() == null ? 0 : stock.getAvailableStock();
                stock.addStock(item.units());
                fill(stock, item, r, purchased);
                Inventory saved = inventoryRepository.save(stock);
                stockAudit.record(saved, "RESTOCK", before, saved.getAvailableStock(), reference, by, notes);
            }
            items++;
            units += item.units();
        }
        return new Applied(items, units, created);
    }

    private static void fill(Inventory stock, Item item, StockReceipt r, LocalDate purchased) {
        if (item.unitCost() != null && item.unitCost().signum() > 0) {
            stock.setUnitCost(item.unitCost().setScale(2, RoundingMode.HALF_UP));
        }
        if (r.getSupplierName() != null) stock.setSupplierName(r.getSupplierName());
        if (r.getReceiptNumber() != null) stock.setBatchNumber(r.getReceiptNumber());
        stock.setDatePurchased(purchased);
        stock.setLastRestockDate(LocalDateTime.now());
        stock.setLastUpdated(LocalDateTime.now());
    }

    /** Same daily reset as restocking in the portal: a new day starts from the stock on hand. */
    private static void rollOverDay(Inventory item) {
        LocalDate today = LocalDate.now();
        if (today.equals(item.getRecordDate())) return;
        item.setRecordDate(today);
        item.setStartingStock(item.getAvailableStock());
        item.setSoldStock(0);
        item.setAddedStock(0);
        item.setDeductions(BigDecimal.ZERO);
        item.setTotalSales(BigDecimal.ZERO);
        item.setGrossSales(BigDecimal.ZERO);
        item.setNetlSales(BigDecimal.ZERO);
    }

    /** The code the portal gives new items: MERCHANT-NAMEWITHOUTSPACES. */
    static String newCode(String merchantId, String name) {
        return merchantId + "-" + name.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return LocalDate.now();
        try {
            LocalDate d = LocalDate.parse(text.trim());
            return d.isAfter(LocalDate.now()) ? LocalDate.now() : d;
        } catch (DateTimeParseException e) {
            return LocalDate.now();
        }
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
