package com.dayworks_ltd.loyalty_engine.credit_engine.service;

import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceDataDTO;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceItemDTO;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
@Service
@Slf4j

public class InvoiceToInventorySyncService {
    private final InventoryRepository inventoryRepository;

    public InvoiceToInventorySyncService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }


    @Transactional
    public Map<String, Object> syncInvoiceToInventory(String merchantId, InvoiceDataDTO invoiceData) {

        try {
            Logger.getAnonymousLogger().log(Level.INFO,
                    "Syncing invoice " + invoiceData.getInvoice().getInvoiceNumber() +
                            " to inventory for merchant " + merchantId);

            System.out.println("================== Syncing to Inventory =======================");

            List<Map<String, Object>> syncedItems = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            String supplierName = invoiceData.getSupplier().getName();
            String invoiceNumber = invoiceData.getInvoice().getInvoiceNumber();
            LocalDate invoiceDate = invoiceData.getInvoice().getDate();

            // Process each item (similar to your customer loop)
            for (int i = 0; i < invoiceData.getItems().size(); i++) {
                InvoiceItemDTO item = invoiceData.getItems().get(i);

                try {
                    System.out.println("\nSyncing item " + (i + 1) + ": " + item.getNormalizedName() +
                            " (Qty: " + item.getQuantity() + ")");

                    Inventory inventory = syncSingleItem(
                            merchantId,
                            item,
                            supplierName,
                            invoiceNumber,
                            invoiceDate
                    );

                    Map<String, Object> itemResult = new HashMap<>();
                    itemResult.put("itemName", item.getNormalizedName());
                    itemResult.put("quantityAdded", item.getQuantity());
                    itemResult.put("unitCost", item.getUnitCost());
                    itemResult.put("inventoryId", inventory.getId());
                    itemResult.put("action", inventory.getAddedStock() > item.getQuantity() ? "UPDATED" : "CREATED");

                    syncedItems.add(itemResult);

                    System.out.println("✓ Synced successfully");

                } catch (Exception e) {
                    Logger.getAnonymousLogger().log(Level.WARNING,
                            "Failed to sync item: " + item.getRawName(), e);
                    errors.add("Failed to sync " + item.getRawName() + ": " + e.getMessage());
                    System.out.println("✗ Failed: " + e.getMessage());
                }
            }

            System.out.println("================== Inventory Sync Complete =======================");
            System.out.println("Total items: " + invoiceData.getItems().size());
            System.out.println("Synced: " + syncedItems.size());
            System.out.println("Errors: " + errors.size());

            Map<String, Object> result = new HashMap<>();
            result.put("merchantId", merchantId);
            result.put("invoiceNumber", invoiceNumber);
            result.put("supplierName", supplierName);
            result.put("totalItems", invoiceData.getItems().size());
            result.put("syncedItems", syncedItems);
            result.put("errors", errors);
            result.put("success", errors.isEmpty());
            result.put("totalValue", invoiceData.getInvoice().getSubtotal());

            return result;

        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Failed to sync invoice to inventory", e);
            throw new RuntimeException("Inventory sync failed: " + e.getMessage(), e);
        }
    }

    private Inventory syncSingleItem(
            String merchantId,
            InvoiceItemDTO item,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate) {

        String itemName = item.getNormalizedName();

        // Check if item already exists
        Inventory existing = inventoryRepository
                .findByMerchantIdAndItemName(merchantId, itemName)
                .orElse(null);

        if (existing != null) {
            // Update existing
            return updateExistingInventory(existing, item, supplierName, invoiceNumber, invoiceDate);
        } else {
            // Create new
            return createNewInventory(merchantId, item, supplierName, invoiceNumber, invoiceDate);
        }
    }

    private Inventory updateExistingInventory(
            Inventory existing,
            InvoiceItemDTO item,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate) {

        System.out.println("  Updating existing item (current stock: " + existing.getAvailableStock() + ")");

        int currentAdded = existing.getAddedStock() != null ? existing.getAddedStock() : 0;
        int currentAvailable = existing.getAvailableStock() != null ? existing.getAvailableStock() : 0;

        existing.setAddedStock(currentAdded + item.getQuantity());
        existing.setAvailableStock(currentAvailable + item.getQuantity());
        existing.setUnitCost(item.getUnitCost());
        existing.setSupplierName(supplierName);
        existing.setDatePurchased(invoiceDate);
        existing.setBatchNumber(invoiceNumber);
        existing.setLastUpdated(LocalDateTime.now());

        existing.computeAvailableStock();

        return inventoryRepository.save(existing);
    }

    private Inventory createNewInventory(
            String merchantId,
            InvoiceItemDTO item,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate) {

        System.out.println("  Creating new inventory item");

        Inventory inventory = Inventory.builder()
                .merchantId(merchantId)
                .itemName(item.getNormalizedName())
                .itemCode(generateItemCode(item.getNormalizedName()))
                .startingStock(0)
                .addedStock(item.getQuantity())
                .soldStock(0)
                .availableStock(item.getQuantity())
                .closingStock(0)
                .totalSales(BigDecimal.ZERO)
                .grossSales(BigDecimal.ZERO)
                .netlSales(BigDecimal.ZERO)
                .deductions(BigDecimal.ZERO)
                .unitCost(item.getUnitCost())
                .unitPrice(null)
                .supplierName(supplierName)
                .batchNumber(invoiceNumber)
                .datePurchased(invoiceDate)
                .isActive(true)
                .lastUpdated(LocalDateTime.now())
                .recordDate(LocalDate.now())
                .reorderLevel(0)
                .build();

        inventory.computeAvailableStock();

        return inventoryRepository.save(inventory);
    }

    private String generateItemCode(String itemName) {
        String code = itemName
                .toUpperCase()
                .replaceAll("[^A-Z0-9\\s]", "")
                .replaceAll("\\s+", "-");

        if (code.length() > 20) {
            code = code.substring(0, 20);
        }

        return code;
    }
}
