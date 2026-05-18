package com.dayworks_ltd.loyalty_engine.inventory.DTO;


import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransferItemRequest {

    private String itemCode;
    private String itemName;
    private Integer quantity;
    private BigDecimal wholesaleUnitPrice;
    private String remarks;
}