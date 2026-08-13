package com.dayworks_ltd.loyalty_engine.orders.dto;

import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;

import java.util.List;

public record OrderFulfillmentResult(
        StockTransfer stockTransfer,
        List<InsufficientOrderItem> insufficientItems
) {}