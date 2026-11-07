package com.dayworks_ltd.loyalty_engine.inventory.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The shop's business day for stock counts. Shops often count around midnight, so anything
 * done before the cutoff hour (default 05:00) belongs to the previous day — a count finished at
 * 00:06 closes yesterday, it does not use up today.
 */
@Component
public class TradingDay {

    @Value("${stock-count.day-cutoff-hour:5}")
    private int cutoffHour;

    public LocalDate today() {
        return LocalDateTime.now().minusHours(cutoffHour).toLocalDate();
    }
}
