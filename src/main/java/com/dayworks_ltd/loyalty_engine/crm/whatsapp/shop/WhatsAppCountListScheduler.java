package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 8:30 PM (Nairobi): sends each configured counter tonight's list of items that sold or were restocked, on WhatsApp.
 * Configured as stock-count.whatsapp-list=MERCHANT:PHONE[,MERCHANT:PHONE...], e.g. 23:0797770952.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppCountListScheduler {

    private final WhatsAppStockCount stockCount;

    @Value("${stock-count.whatsapp-list:}")
    private List<String> targets = List.of();

    @Scheduled(cron = "${stock-count.whatsapp-list-cron:0 30 20 * * *}", zone = "${stock-count.zone:Africa/Nairobi}")
    public void sendCountLists() {
        for (String target : targets) {
            String[] parts = target.trim().split(":");
            if (parts.length != 2) {
                log.warn("stock-count.whatsapp-list entry '{}' should be MERCHANT:PHONE", target);
                continue;
            }
            try {
                boolean sent = stockCount.sendCountList(parts[0].trim(), parts[1].trim());
                log.info("Count list for merchant {} to {}: {}", parts[0].trim(), parts[1].trim(), sent ? "sent" : "nothing to send");
            } catch (Exception e) {
                log.error("Count list for merchant {} failed: {}", parts[0].trim(), e.getMessage(), e);
            }
        }
    }
}
