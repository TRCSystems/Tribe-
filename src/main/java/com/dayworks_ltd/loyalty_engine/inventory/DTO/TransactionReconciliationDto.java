package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class TransactionReconciliationDto {
    private LocalDate date;
    private String merchantId;
    private Integer totalTransactions;
    private Integer totalUnits;
    private BigDecimal totalRevenue;
    private List<BasketDto> transactions;
}