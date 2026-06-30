package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WholesaleCatalogDto {
    private Long inventoryId;
    private String itemName;
    private String itemCode;
    private String availability;   // "IN_STOCK" | "LOW_STOCK" | "OUT_OF_STOCK"

    private BigDecimal wholesalePrice;
    private Integer availableStock;
    private String productImageUrl;
    private String productCategory;
    private String productBrand;
}