package com.dayworks_ltd.loyalty_engine.orders.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record InsufficientOrderItem(
        String itemCode,
        String itemName,
        Integer quantity,
        BigDecimal wholesalePrice,
        Integer availableStock,
        String reason
) {
    /** How many units of this line can't be shipped (ordered minus what's in stock). */
    @JsonProperty("shortBy")
    public int shortBy() {
        int ordered = quantity == null ? 0 : quantity;
        int available = availableStock == null ? 0 : Math.max(availableStock, 0);
        return Math.max(ordered - available, 0);
    }
}
