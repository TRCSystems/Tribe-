package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryResponseDto {

    private Long id;
    private String merchantId;
    private String itemName;
    private String itemCode;

    // Stock fields
    private Integer startingStock;
    private Integer addedStock;
    private Integer soldStock;
    private Integer availableStock;
    private Integer closingStock;

    // Sales fields
    private BigDecimal totalSales;
    private BigDecimal grossSales;
    private BigDecimal netlSales;
    private BigDecimal deductions;

    private BigDecimal unitCost;
    private BigDecimal unitPrice;        // This will be normal or wholesale price
    private String expenseNote;

    private Boolean isActive;
    private String productImageUrl;
    private String productDescription;
    private String productCategory;
    private String batchNumber;
    private String supplierName;
    private LocalDate datePurchased;
    private LocalDate expiryDate;
    private Integer reorderLevel;
    private LocalDateTime lastUpdated;
    private String productBrand;
    private LocalDate recordDate;


    private BigDecimal wholesalePrice;
    private String priceType;
}