package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BasketDto {
    private String transactionRef;
    private LocalDateTime saleDatetime;
    private String customerPhone;
    private Integer itemCount;
    private Integer totalUnits;
    private BigDecimal basketTotal;
    private List<BasketLineDto> items;
}
