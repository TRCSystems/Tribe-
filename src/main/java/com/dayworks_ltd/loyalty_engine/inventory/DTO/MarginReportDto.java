package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class MarginReportDto {
    private LocalDate date;
    private String merchantId;
    private MarginTotalsDto dailyTotal;
    private List<OrderTypeMarginDto> byOrderType;
    private List<ItemMarginDto> byItem;
}