package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;

public interface MarginTotalsProjection {
    Integer getUnitsSold();
    BigDecimal getGrossRevenue();
    BigDecimal getTotalCost();
    BigDecimal getGrossMargin();
    BigDecimal getMarginPercentage();
}