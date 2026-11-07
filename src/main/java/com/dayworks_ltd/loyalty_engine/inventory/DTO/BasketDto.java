package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BasketDto {
    private String transactionRef;
    private LocalDateTime saleDatetime;
    private String merchantName;
    private String merchantPhone;
    private Integer itemCount;
    private Integer totalUnits;
    private BigDecimal basketTotal;
    /** What was made on this sale: basket total - cost of the items. Null if any line has no cost. */
    private BigDecimal basketCost;
    private BigDecimal basketMargin;
    private BigDecimal marginPct;
    private Boolean marginComplete;
    private List<BasketLineDto> items;
}
