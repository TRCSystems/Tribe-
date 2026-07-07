package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SaleItemRequest {
    private Long inventoryId;
    private int quantity;
    private BigDecimal discount;  // positive = discount, negative = extra charge, null = 0
    private String orderType;

}