package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MarginTotalsDto {
    private Integer unitsSold;
    private BigDecimal grossRevenue;
    private BigDecimal totalCost;
    private BigDecimal grossMargin;
    private BigDecimal marginPercentage;
}