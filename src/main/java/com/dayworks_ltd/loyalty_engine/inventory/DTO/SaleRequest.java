package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.util.List;

@Data
public class SaleRequest {
    private String merchantId;
    private String customerPhone;
    private List<SaleItemRequest> items;
    private String orderType; // "RETAIL" or "WHOLESALE" — applies to entire order
}
