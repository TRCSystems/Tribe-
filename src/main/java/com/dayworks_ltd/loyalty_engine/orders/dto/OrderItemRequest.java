package com.dayworks_ltd.loyalty_engine.orders.dto;



import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderItemRequest {

    private String itemCode;
    private String itemName;
    private Integer quantity;
    private BigDecimal wholesalePrice;   // Price per unit from distributor
}