package com.dayworks_ltd.loyalty_engine.orders.dto;

import java.math.BigDecimal;

public record InsufficientOrderItem(
        String itemCode,
        String itemName,
        Integer quantity,
        BigDecimal wholesalePrice,
        Integer availableStock,
        String reason
) {
}
