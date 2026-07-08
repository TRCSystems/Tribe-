package com.dayworks_ltd.loyalty_engine.orders;

import com.dayworks_ltd.loyalty_engine.orders.dto.InsufficientOrderItem;

import java.util.ArrayList;
import java.util.List;

public class CannotFulfillOrderException extends RuntimeException {
    public List<InsufficientOrderItem> insufficientOrderItems = new ArrayList<>();
    public CannotFulfillOrderException(String message, List<InsufficientOrderItem> insufficientOrderItems) {
        super(message);
        this.insufficientOrderItems = insufficientOrderItems;
    }

    public List<InsufficientOrderItem> getInsufficientOrderItems() {
        return insufficientOrderItems;
    }
}
