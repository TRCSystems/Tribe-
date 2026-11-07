package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryStockAudit;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryStockAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Writes one row to the stock audit trail. Every change to an item's quantity should go through
 * here (or write the same row), so the audit trail report can explain every unit.
 */
@Component
@RequiredArgsConstructor
public class StockAuditRecorder {

    /** Stock loaded from a CSV / Excel file. */
    public static final String IMPORT = "IMPORT";
    /** Item created with an opening quantity. */
    public static final String NEW_ITEM = "NEW_ITEM";
    /** Stock received from an approved supplier invoice. */
    public static final String INVOICE_RECEIPT = "INVOICE_RECEIPT";
    /** Quantity typed in directly by an admin. */
    public static final String MANUAL_CORRECTION = "MANUAL_CORRECTION";

    private final InventoryStockAuditRepository auditRepository;

    public void record(Inventory item, String actionType, Integer before, Integer after,
                       String reference, String performedBy, String notes) {
        if (item == null || item.getId() == null) return;
        int b = before == null ? 0 : before;
        int a = after == null ? 0 : after;
        if (a == b && !NEW_ITEM.equals(actionType)) return; // nothing moved
        auditRepository.save(InventoryStockAudit.builder()
                .inventoryId(item.getId())
                .itemCode(item.getItemCode())
                .merchantId(item.getMerchantId())
                .actionType(actionType)
                .availableStockBefore(b)
                .availableStockAfter(a)
                .reference(reference)
                .performedBy(performedBy)
                .notes(notes)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
