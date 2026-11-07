package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Guarantees a discrepancy report exists for every trading day: open counts are finalized,
 * and merchants that traded without counting get a report with every queued item NOT_COUNTED.
 * Runs on server time, the same clock used for sale dates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCountScheduler {

    private final StockCountService stockCountService;

    /**
     * 9 PM (Nairobi) every day: prepare each trading shop's count list and SMS every number on its
     * list how many items to count. The cron runs in shop time even if the server clock is UTC.
     */
    @Scheduled(cron = "${stock-count.reminder-cron:0 0 21 * * *}", zone = "${stock-count.zone:Africa/Nairobi}")
    public void sendCountReminders() {
        LocalDate today = stockCountService.today();
        int sent = 0, failed = 0;
        for (String merchantId : stockCountService.merchantsToRemind(today)) {
            try {
                if (stockCountService.prepareCountAndRemind(merchantId) > 0) sent++;
            } catch (Exception e) {
                failed++;
                log.error("Count reminder failed for merchant {}: {}", merchantId, e.getMessage(), e);
            }
        }
        log.info("Stock count reminders {}: {} shops reminded, {} failed", today, sent, failed);
    }

    @Scheduled(cron = "${stock-count.auto-finalize-cron:0 30 23 * * *}")
    public void finalizeTodaysCounts() {
        LocalDate today = stockCountService.today();
        int finalized = 0, skipped = 0, failed = 0;

        // Yesterday's too: a count reopened after last night's close (nothing had been counted) and never finished
        List<CloseDay> sessions = new ArrayList<>(stockCountService.findOpenSessions(today.minusDays(1)));
        sessions.addAll(stockCountService.findOpenSessions(today));
        for (CloseDay open : sessions) {
            try {
                stockCountService.finalizeSession(open, "SYSTEM", "Auto-finalized at end of day");
                finalized++;
            } catch (Exception e) {
                failed++;
                log.error("Auto-finalize failed for merchant {}: {}", open.getMerchantId(), e.getMessage(), e);
            }
        }

        for (String merchantId : stockCountService.findMerchantsThatSkippedCount(today)) {
            try {
                stockCountService.recordSkippedCount(merchantId, today);
                skipped++;
            } catch (Exception e) {
                failed++;
                log.error("Recording skipped count failed for merchant {}: {}", merchantId, e.getMessage(), e);
            }
        }

        log.info("Stock count auto-finalize {}: {} finalized, {} skipped counts recorded, {} failed",
                today, finalized, skipped, failed);
    }
}
