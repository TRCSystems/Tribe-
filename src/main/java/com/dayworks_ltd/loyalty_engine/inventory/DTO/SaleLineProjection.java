package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface SaleLineProjection {
    String getTransactionRef();
    LocalDateTime getSaleDatetime();
    String getCustomerPhone();
    String getItemCode();
    String getItemName();
    Integer getQuantity();
    BigDecimal getUnitPrice();
    BigDecimal getTotalPrice();
    String getOrderType();
    String getMerchantName();
    String getMerchantPhone();
}