package com.dayworks_ltd.loyalty_engine.inventory.services;


import com.anthropic.errors.UnauthorizedException;
import com.dayworks_ltd.loyalty_engine.auth.enums.TransactionType;
import com.dayworks_ltd.loyalty_engine.auth.enums.TransferStatus;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferItemRequest;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferRequest;
import com.dayworks_ltd.loyalty_engine.inventory.models.*;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.StockTransferRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockTransferService {

    private final StockTransferRepository stockTransferRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final MerchantRepository merchantRepository;

    /**
     * Distributor creates a new Stock Transfer (Manual Pickup or Order Fulfillment)
     */
    @Transactional
    public StockTransfer createStockTransfer(StockTransferRequest request, Long issuedByUserId) {

        Merchant distributor = merchantRepository.findById(request.getDistributorId())
                .orElseThrow(() -> new RuntimeException("Distributor not found"));

        Merchant recipient = merchantRepository.findById(request.getRecipientId())
                .orElseThrow(() -> new RuntimeException("Recipient merchant not found"));

        // Generate unique transfer code
        String transferCode = "ST-" + LocalDateTime.now().getYear() +
                String.format("%02d", LocalDateTime.now().getMonthValue()) +
                "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        StockTransfer stockTransfer = StockTransfer.builder()
                .transferCode(transferCode)
                .distributor(distributor)
                .recipient(recipient)
                .transferType(request.getTransferType())
                .status(TransferStatus.ISSUED)
                .issueDate(LocalDateTime.now())
                .issuedByUserId(issuedByUserId)
                .notes(request.getNotes())
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (StockTransferItemRequest itemReq : request.getItems()) {
            StockTransferItem item = StockTransferItem.builder()
                    .itemCode(itemReq.getItemCode())
                    .itemName(itemReq.getItemName())
                    .quantityIssued(itemReq.getQuantity())
                    .wholesaleUnitPrice(itemReq.getWholesaleUnitPrice())
                    .lineTotal(itemReq.getWholesaleUnitPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity())))
                    .remarks(itemReq.getRemarks())
                    .build();

            stockTransfer.addItem(item);
            totalAmount = totalAmount.add(item.getLineTotal());

            // === Deduct stock from Distributor immediately ===
            deductStockFromDistributor(distributor.getId().toString(), itemReq.getItemCode(),
                    itemReq.getQuantity(), issuedByUserId, transferCode);
        }

        stockTransfer.setTotalWholesaleAmount(totalAmount);
        StockTransfer savedTransfer = stockTransferRepository.save(stockTransfer);

        log.info("StockTransfer created: {} from Distributor {} to Merchant {}",
                transferCode, distributor.getBusinessName(), recipient.getBusinessName());

        return savedTransfer;
    }

    /**
     * Merchant confirms receipt of stock
     */
    @Transactional
    public StockTransfer confirmReceipt(Long transferId, Long receivedByUserId, String merchantId, String notes) {

        StockTransfer transfer = stockTransferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Stock transfer not found"));

        // SECURITY: verify the caller is actually the intended recipient
        String recipientId = transfer.getRecipient().getId().toString();
        if (!recipientId.equals(merchantId)) {
            throw new RuntimeException("You are not the recipient of this transfer");
        }

        if (transfer.getStatus() != TransferStatus.ISSUED) {
            throw new RuntimeException("Transfer cannot be confirmed. Current status: " + transfer.getStatus());
        }

        transfer.setStatus(TransferStatus.RECEIVED);
        transfer.setReceivedDate(LocalDateTime.now());
        transfer.setReceivedByUserId(receivedByUserId);
        if (notes != null) {
            transfer.setNotes(notes);
        }

        // Use merchantId parameter — NOT transfer.getRecipient().getId()
        for (StockTransferItem item : transfer.getItems()) {
            addStockToMerchant(
                    merchantId,          // ← THIS is what you changed, use it
                    item.getItemCode(),
                    item.getItemName(),
                    item.getQuantityIssued(),
                    item.getWholesaleUnitPrice(),
                    receivedByUserId,
                    transfer.getTransferCode()
            );
        }

        StockTransfer saved = stockTransferRepository.save(transfer);
        log.info("StockTransfer {} confirmed by merchant {}", transfer.getTransferCode(), merchantId);

        return saved;
    }

    /**
     * Get pending receipts for a merchant (what they need to confirm)
     */
    public List<StockTransfer> getPendingReceipts(String merchantId) {
        Merchant merchant = merchantRepository.findById(Long.parseLong(merchantId))
                .orElseThrow(() -> new RuntimeException("Merchant not found"));

        return stockTransferRepository.findPendingReceipts(merchant);
    }

    /**
     * Get all transfers initiated by a distributor
     */
    public List<StockTransfer> getTransfersByDistributor(String distributorId) {
        Merchant distributor = merchantRepository.findById(Long.parseLong(distributorId))
                .orElseThrow(() -> new RuntimeException("Distributor not found"));

        return stockTransferRepository.findByDistributorAndStatusOrderByIssueDateDesc(
                distributor, TransferStatus.ISSUED);
    }

    // ====================== Private Helper Methods ======================

    private void deductStockFromDistributor(String distributorMerchantId, String itemCode,
                                            Integer quantity, Long userId, String referenceCode) {

        Inventory inventory = inventoryRepository.findByMerchantIdAndItemCode(distributorMerchantId, itemCode)
                .orElseThrow(() -> new RuntimeException("Item not found in distributor inventory: " + itemCode));

        // Deduct from available stock
        inventory.setAvailableStock(inventory.getAvailableStock() - quantity);
        inventory.setSoldStock(inventory.getSoldStock() + quantity); // Treating as "sold" to merchant
        inventory.setClosingStock(inventory.getAvailableStock());

        inventoryRepository.save(inventory);

        // Create Audit Trail
        createInventoryTransaction(inventory.getMerchantId(), itemCode, inventory.getItemName(),
                TransactionType.STOCK_TRANSFER_ISSUED, -quantity, userId, "STOCK_TRANSFER", referenceCode);
    }

    private void addStockToMerchant(String merchantId, String itemCode, String itemName,
                                    Integer quantity, BigDecimal wholesalePrice,
                                    Long userId, String referenceCode) {

        Inventory inventory = inventoryRepository.findByMerchantIdAndItemCode(merchantId, itemCode)
                .orElseGet(() -> createNewInventoryItem(merchantId, itemCode, itemName, wholesalePrice));

        // Increase addedStock and availableStock
        inventory.setAddedStock(inventory.getAddedStock() + quantity);
        inventory.setAvailableStock(inventory.getAvailableStock() + quantity);
        inventory.setClosingStock(inventory.getAvailableStock());
        inventory.setLastUpdated(LocalDateTime.now());

        inventoryRepository.save(inventory);

        // Create Audit Trail
        createInventoryTransaction(merchantId, itemCode, itemName,
                TransactionType.STOCK_TRANSFER_RECEIVED, quantity, userId, "STOCK_TRANSFER", referenceCode);
    }

    private Inventory createNewInventoryItem(String merchantId, String itemCode,
                                             String itemName, BigDecimal wholesalePrice) {
        Inventory newItem = Inventory.builder()
                .merchantId(merchantId)
                .itemName(itemName)
                .itemCode(itemCode)
                .unitPrice(wholesalePrice.multiply(new BigDecimal("1.3"))) // Example markup
                .unitCost(wholesalePrice)
                .startingStock(0)
                .addedStock(0)
                .soldStock(0)
                .availableStock(0)
                .closingStock(0)
                .isActive(true)
                .recordDate(LocalDate.now())
                .build();

        return inventoryRepository.save(newItem);
    }

    private void createInventoryTransaction(String merchantId, String itemCode, String itemName,
                                            TransactionType type, Integer quantity,
                                            Long userId, String referenceType, String referenceCode) {

        // Note: You may need to fetch Merchant entity here if required
        // For now, we are using merchantId as String in Inventory

        InventoryTransaction tx = InventoryTransaction.builder()
                .merchantId(merchantId)           // Adjust if your InventoryTransaction uses Merchant object
                .itemCode(itemCode)
                .itemName(itemName)
                .transactionType(type)
                .quantity(quantity)
                .performedByUserId(userId)
                .referenceType(referenceType)
                .referenceId(null) // You can store StockTransfer id if needed
                .transactionDate(LocalDateTime.now())
                .notes("Stock Transfer: " + referenceCode)
                .build();

        inventoryTransactionRepository.save(tx);
    }
}