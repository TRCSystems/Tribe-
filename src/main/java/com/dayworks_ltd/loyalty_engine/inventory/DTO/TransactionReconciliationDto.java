package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class TransactionReconciliationDto {
    private LocalDate date;
    private String merchantId;
    private String merchantName;
    private String merchantPhone;
    private Integer totalTransactions;
    private Integer totalUnits;
    private BigDecimal totalRevenue;
    /** Margin for the day across the sales whose costs are all known. */
    private BigDecimal totalCost;
    private BigDecimal totalMargin;
    private BigDecimal marginPct;
    /** How many sales could not be costed (older sales recorded without a unit cost). */
    private Integer salesWithoutCost;

    /** Month to date: from the 1st of the month up to and including this report's date. */
    private LocalDate mtdFrom;
    private Integer mtdSales;
    private BigDecimal mtdRevenue;
    private BigDecimal mtdMargin;
    private BigDecimal mtdMarginPct;
    /** Sale lines this month with no recorded cost (left out of the margin). */
    private Integer mtdLinesWithoutCost;
    private List<BasketDto> transactions;
}