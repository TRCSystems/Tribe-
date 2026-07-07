package com.dayworks_ltd.loyalty_engine.orders.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDTO {
    private Long id;
    private String itemCode;
    private String itemName;
    private Integer quantity;
    private BigDecimal wholesalePrice;
    private BigDecimal lineTotal;
}