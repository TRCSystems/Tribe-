package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.repositories.SaleTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * End of month: every shop that sold something that month gets an SMS (registered phone plus
 * report recipients who receive summaries) with what it sold, what it made and its closing stock.
 * Runs at 23:55 Kenya time on the last day of the month so the closing stock is the month-end figure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlySummaryScheduler {

    private final SalesSummaryService salesSummaryService;
    private final SaleTransactionRepository saleTransactionRepository;

    @Scheduled(cron = "${reports.monthly-summary-cron:0 55 23 L * *}", zone = "${stock-count.zone:Africa/Nairobi}")
    public void sendMonthlySummaries() {
        YearMonth month = YearMonth.from(LocalDate.now());
        int sent = 0, failed = 0;
        for (String merchantId : saleTransactionRepository.findMerchantIdsWithSalesBetween(
                month.atDay(1), month.atEndOfMonth())) {
            try {
                salesSummaryService.sendMonthlySummary(merchantId, month);
                sent++;
            } catch (Exception e) {
                failed++;
                log.error("Monthly summary failed for merchant {}: {}", merchantId, e.getMessage(), e);
            }
        }
        log.info("Monthly summaries {}: {} sent, {} failed", month, sent, failed);
    }
}
