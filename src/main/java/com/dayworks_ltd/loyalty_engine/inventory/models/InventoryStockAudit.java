package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_stock_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryStockAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long inventoryId;
    @Column(nullable = false) private String itemCode;
    @Column(nullable = false) private String merchantId;

    @Column(nullable = false) private String actionType; // SALE, RESTOCK, MANUAL_CORRECTION, REVERSAL

    @Column private Integer availableStockBefore;
    @Column private Integer availableStockAfter;
    @Column private Integer soldStockBefore;
    @Column private Integer soldStockAfter;
    @Column private Integer addedStockBefore;
    @Column private Integer addedStockAfter;

    @Column(precision = 12, scale = 2) private BigDecimal totalSalesBefore;
    @Column(precision = 12, scale = 2) private BigDecimal totalSalesAfter;

    @Column private String reference;   // transactionRef, correction ticket, etc.
    @Column private String performedBy; // userId or "SYSTEM"
    @Column private String notes;       // free text — e.g. "supplier-fulfilled, reverting"

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    private LocalDateTime createdAtInternal = LocalDateTime.now();
}