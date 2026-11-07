package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.smm.comments.SellerAlert;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmHandover;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCommentRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmHandoverRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Urgent WhatsApp notifications to the seller about hidden bad comments. Only a notification (see {@link SellerAlert});
 * the seller reads the details in the portal. At most one an hour per seller: comments arriving inside that hour are
 * counted into the next one by the sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmAlertService {

    /** Meta template (utility) the notification goes out as; register it once with {@link #templateDefinition}. */
    public static final String TEMPLATE = "sokoreply_bad_comment";

    private final SmmHandoverRepository handovers;
    private final SmmCommentRepository comments;
    private final SmmSellerProfileRepository profiles;
    private final WhatsAppCloudClient whatsapp;
    private final WhatsAppProperties whatsappProps;

    public void maybeAlert(Long merchantId) {
        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        if (handovers.existsByMerchantIdAndUrgentTrueAndAlertedAtAfter(merchantId, now.minusHours(1))) return;
        List<SmmHandover> pending = handovers.findByMerchantIdAndUrgentTrueAndAlertedAtIsNull(merchantId);
        if (pending.isEmpty()) return;
        String to = profiles.findByMerchantId(merchantId).map(p -> p.getWhatsappNumber()).orElse(null);
        if (to == null || to.isBlank() || !whatsappProps.canSend()) {
            log.warn("Urgent SokoReply alert for merchant {} not sent: {}", merchantId,
                    to == null || to.isBlank() ? "no WhatsApp number in the seller profile" : "WhatsApp sending is off");
            return;
        }
        Set<SocialPlatform> where = EnumSet.noneOf(SocialPlatform.class);
        pending.forEach(h -> comments.findById(h.getCommentId()).map(SmmComment::getPlatform).ifPresent(where::add));
        WhatsAppCloudClient.Sent sent = whatsapp.sendTemplate(to, TEMPLATE, templateParams(pending.size(), where), null);
        if (!sent.ok()) {
            log.warn("Urgent SokoReply alert for merchant {} failed: {}", merchantId, sent.error());
            return;
        }
        pending.forEach(h -> {
            h.setAlertedAt(now);
            h.setSentTo(to);
        });
        handovers.saveAll(pending);
    }

    /** Every 10 minutes: alerts held back by the one-an-hour rule go out once the hour has passed. */
    @Scheduled(fixedDelayString = "${smm.alert-sweep-ms:600000}", initialDelay = 60000)
    public void sweep() {
        handovers.findByUrgentTrueAndAlertedAtIsNull().stream().map(SmmHandover::getMerchantId).distinct().forEach(m -> {
            try {
                maybeAlert(m);
            } catch (Exception e) {
                log.warn("Alert sweep failed for merchant {}: {}", m, e.getMessage());
            }
        });
    }

    /** {{1}} = "A bad comment" / "4 bad comments", {{2}} = where. */
    static List<String> templateParams(int count, Set<SocialPlatform> where) {
        String place = where.isEmpty() ? "Facebook" : String.join(" and ", where.stream()
                .map(p -> p.name().charAt(0) + p.name().substring(1).toLowerCase(Locale.ROOT)).toList());
        return List.of(count <= 1 ? "A bad comment" : count + " bad comments", place);
    }

    /** The template to register with Meta (utility). Its wording matches {@link SellerAlert}. */
    public static Map<String, Object> templateDefinition(String language) {
        String text = "⚠️ Tribe: {{1}} needing your intervention received on {{2}}. We have hidden it from the public. "
                + "Please log in to Tribe to see the details and act promptly: https://tribessystems.co.ke";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", text);
        body.put("example", Map.of("body_text", List.of(List.of("A bad comment", "Facebook"))));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", TEMPLATE);
        def.put("language", language);
        def.put("category", "UTILITY");
        def.put("components", List.of(body));
        return def;
    }
}
