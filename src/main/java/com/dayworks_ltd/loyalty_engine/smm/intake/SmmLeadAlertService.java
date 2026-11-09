package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.smm.model.Lead;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmLeadRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A WhatsApp to the seller when a buyer is ready to buy (wants it now, wants to book, or left a phone number), so the
 * seller can call while the buyer is still keen. The agents have already replied; this is the hand-off to a person.
 * At most one alert every 15 minutes per seller: buyers who turn hot in between go out together in the next one.
 * Buttons: "Called ✅" marks them contacted, "Pause agents" stops the agents' replies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmLeadAlertService {

    public static final String TEMPLATE = "sokoreply_hot_lead";
    static final Duration GAP = Duration.ofMinutes(15);
    /** A buyer who turned hot more than a day ago is no longer news. */
    static final Duration STALE = Duration.ofHours(24);

    private final SmmLeadRepository leads;
    private final SmmSellerProfileRepository profiles;
    private final WhatsAppCloudClient whatsapp;
    private final WhatsAppProperties whatsappProps;

    public void maybeAlert(Long merchantId) {
        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        if (leads.existsByMerchantIdAndHotAlertedAtAfter(merchantId, now.minus(GAP))) return; // the sweep sends it later
        List<Lead> pending = new ArrayList<>(leads.findByMerchantIdAndHotAlertPendingTrueOrderByLastContactAtAsc(merchantId));
        List<Lead> stale = pending.stream().filter(l -> l.getLastContactAt() != null && l.getLastContactAt().isBefore(now.minus(STALE))).toList();
        if (!stale.isEmpty()) {
            stale.forEach(l -> l.setHotAlertPending(false));
            leads.saveAll(stale);
            pending.removeAll(stale);
        }
        if (pending.isEmpty()) return;
        String to = profiles.findByMerchantId(merchantId).map(p -> p.getWhatsappNumber()).orElse(null);
        if (to == null || to.isBlank() || !whatsappProps.canSend()) {
            log.warn("SokoReply hot-lead alert for merchant {} not sent: {}", merchantId,
                    to == null || to.isBlank() ? "no WhatsApp number in the seller profile" : "WhatsApp sending is off");
            return;
        }
        WhatsAppCloudClient.Sent sent = whatsapp.sendTemplateWithReplies(to, TEMPLATE, params(pending),
                List.of(calledPayload(pending), "SMM_PAUSE"));
        if (!sent.ok()) {
            log.warn("SokoReply hot-lead alert for merchant {} failed: {}", merchantId, sent.error());
            return;
        }
        log.info("SokoReply: hot-lead alert for {} buyer(s) sent to merchant {}", pending.size(), merchantId);
        pending.forEach(l -> {
            l.setHotAlertPending(false);
            l.setHotAlertedAt(now);
        });
        leads.saveAll(pending);
    }

    /** Every 5 minutes: alerts held back by the 15-minute gap go out. */
    @Scheduled(fixedDelayString = "${smm.lead-alert-sweep-ms:300000}", initialDelay = 90000)
    public void sweep() {
        leads.findByHotAlertPendingTrue().stream().map(Lead::getMerchantId).distinct().forEach(m -> {
            try {
                maybeAlert(m);
            } catch (Exception e) {
                log.warn("Hot-lead sweep failed for merchant {}: {}", m, e.getMessage());
            }
        });
    }

    /** {{1}} where, {{2}} who, {{3}} what they want, {{4}} how to reach them. The latest buyer leads. */
    static List<String> params(List<Lead> pending) {
        Lead latest = pending.get(pending.size() - 1);
        String where = latest.getPlatform() == null ? "Facebook"
                : latest.getPlatform().name().charAt(0) + latest.getPlatform().name().substring(1).toLowerCase(Locale.ROOT);
        String name = latest.getSenderName() == null || latest.getSenderName().isBlank() ? "A buyer" : latest.getSenderName().trim();
        String who = pending.size() == 1 ? name : name + " and " + (pending.size() - 1) + " more";
        String want = latest.getHotWant() == null || latest.getHotWant().isBlank() ? "to buy" : latest.getHotWant();
        if (latest.getInterestedItemName() != null && !latest.getInterestedItemName().isBlank()) {
            want = latest.getInterestedItemName().trim() + " (" + want + ")";
        }
        List<String> phones = pending.stream().map(Lead::getPhoneNumber).filter(p -> p != null && !p.isBlank())
                .distinct().limit(3).toList();
        String contact = phones.isEmpty() ? "no number left, they have our DM"
                : String.join(", ", phones) + (phones.size() == 1 ? " (left in a comment, we hid it)" : " (left in comments, we hid them)");
        return List.of(where, who, want, contact);
    }

    /** What "Called ✅" sends back: the leads it covers. */
    static String calledPayload(List<Lead> pending) {
        return "SMM_CALLED:" + pending.stream().map(l -> String.valueOf(l.getId())).limit(30).collect(Collectors.joining(","));
    }

    /** The template to register with Meta (utility), with its two quick-reply buttons. */
    public static Map<String, Object> templateDefinition(String language) {
        String text = "🔥 Tribe: hot lead on {{1}}. Buyer: {{2}}. Wants: {{3}}. Contact: {{4}}. "
                + "Our agents have already replied to them. Call or message them now to close the sale. "
                + "All your leads: https://tribessystems.co.ke";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", text);
        body.put("example", Map.of("body_text", List.of(List.of("Facebook", "Jane Wanjiru", "black, size 12", "0712345678 (left in a comment, we hid it)"))));
        Map<String, Object> buttons = Map.of("type", "BUTTONS", "buttons", List.of(
                Map.of("type", "QUICK_REPLY", "text", "Called ✅"),
                Map.of("type", "QUICK_REPLY", "text", "Pause agents")));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", TEMPLATE);
        def.put("language", language);
        def.put("category", "UTILITY");
        def.put("components", List.of(body, buttons));
        return def;
    }
}
