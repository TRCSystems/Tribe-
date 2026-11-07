package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateItemRequest {
    private String merchantId;        // Required for security

    private String itemName;          // Optional
    private Integer quantity;         // Optional (updates availableStock)
    private BigDecimal unitPrice;     // Optional (selling / retail price)
    private BigDecimal unitCost;      // Optional (owner only)
    private BigDecimal wholesalePrice;        // Optional (owner only)
    private Boolean clearWholesalePrice;      // Optional: true removes the wholesale price (owner only)

}