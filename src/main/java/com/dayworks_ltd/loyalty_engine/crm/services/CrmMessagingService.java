package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.crm.channels.MessageChannelSender;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmMessage;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmMessageRepository;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppTemplates;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * The one place CRM messages go out. Applies the guardrails, picks the channel, and logs every
 * message — including the ones deliberately not sent — so results can be measured.
 *
 * Guardrails (marketing templates only; order updates always go out):
 *  - shop opted out (STOP)
 *  - hold-out group: a fixed slice of shops never gets marketing, so we can measure real uplift
 *  - frequency cap: at most N marketing messages per shop per 7 days
 *  - quiet hours: no marketing outside the allowed window (Kenya time)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmMessagingService {

    private final CrmMessageRepository messageRepository;
    private final CrmContactService contactService;
    private final MerchantService merchantService;
    private final List<MessageChannelSender> senders;
    private final ShopOrderLinkService linkService;

    @Value("${crm.marketing.max-per-week:3}")
    private int maxMarketingPerWeek;

    /** Percentage of shops (0-100) held out from marketing to measure uplift. */
    @Value("${crm.marketing.holdout-percent:10}")
    private int holdoutPercent;

    @Value("${crm.marketing.quiet-start:19:00}")
    private String quietStart;

    @Value("${crm.marketing.quiet-end:08:00}")
    private String quietEnd;

    /** Tribe WhatsApp number (254…) for the WhatsApp invite's wa.me link. */
    @Value("${whatsapp.display-number:}")
    private String whatsappNumber;

    /**
     * Renders and sends a template to one shop.
     *
     * @param vars        placeholder values; {shop} and {distributor} are filled in automatically
     * @param campaignKey which automation or campaign this belongs to (for reporting)
     * @param sentByUserId the user who triggered it, or null for automations
     */
    public CrmMessage send(Long distributorId, Long shopId, CrmTemplate template, Map<String, String> vars,
                           String campaignKey, Long sentByUserId) {
        CrmContact contact = contactService.getOrCreate(distributorId, shopId);

        Map<String, String> all = new HashMap<>(vars == null ? Map.of() : vars);
        all.putIfAbsent("shop", shortName(merchantName(shopId)));
        all.putIfAbsent("distributor", shortName(merchantName(distributorId)));
        all.replaceAll((k, v) -> smsSafe(v));
        if (template == CrmTemplate.WHATSAPP_INVITE) {
            if (whatsappNumber == null || whatsappNumber.isBlank()) {
                throw new IllegalArgumentException("The Tribe WhatsApp number is not set up yet");
            }
            all.put("walink", "https://wa.me/" + whatsappNumber.replaceAll("\\D", "") + "?text=Order");
        }

        // Every message with {link} gets its own one-time ordering link for this shop
        ShopOrderLinkService.NewLink link = null;
        if (template.needsLink() && (all.get("link") == null || all.get("link").isBlank())) {
            link = linkService.create(distributorId, shopId, null);
            all.put("link", link.url());
        }
        String body = template.render(all);

        CrmMessage.CrmMessageBuilder msg = CrmMessage.builder()
                .distributorId(distributorId)
                .shopMerchantId(shopId)
                .template(template)
                .campaignKey(campaignKey)
                .marketing(template.isMarketing())
                .body(body)
                .sentByUserId(sentByUserId);

        String skip = skipReason(contact, template, distributorId, shopId);
        if (skip != null) {
            CrmMessage skipped = messageRepository.save(msg.status(CrmEnums.MessageStatus.SKIPPED).skipReason(skip).build());
            if (link != null) linkService.attachMessage(link.link().getId(), skipped.getId());
            return skipped;
        }
        MessageChannelSender sender = pickSender(contact, template);
        String to = sender.channel() == CrmEnums.Channel.WHATSAPP && contact.getWhatsappNumber() != null
                ? contact.getWhatsappNumber()
                : contact.getPhone();

        // WhatsApp's "Order now" button needs a one-time token even when the caller supplied its own link
        if (sender.channel() == CrmEnums.Channel.WHATSAPP && link == null && template.needsLink()) {
            link = linkService.create(distributorId, shopId, null);
        }
        final ShopOrderLinkService.NewLink orderLink = link;

        MessageChannelSender.Result result;
        try {
            result = sender.deliver(new MessageChannelSender.Outbound(to, body, template, all,
                    orderLink == null ? null : orderLink.token()));
        } catch (Exception e) {
            log.warn("CRM {} to shop {} failed: {}", sender.channel(), shopId, e.getMessage());
            result = new MessageChannelSender.Result(false, null);
        }
        // WhatsApp refused it outright (e.g. template not approved yet): send the SMS version instead
        if (!result.ok() && sender.channel() == CrmEnums.Channel.WHATSAPP && contact.getPhone() != null) {
            MessageChannelSender sms = byChannel(CrmEnums.Channel.SMS);
            if (sms != null) {
                sender = sms;
                to = contact.getPhone();
                msg.skipReason("WhatsApp refused the message, sent by SMS instead");
                try {
                    result = sms.deliver(new MessageChannelSender.Outbound(to, body, template, all, null));
                } catch (Exception e) {
                    log.warn("CRM SMS fallback to shop {} failed: {}", shopId, e.getMessage());
                }
            }
        }
        CrmMessage saved = messageRepository.save(msg
                .channel(sender.channel())
                .toNumber(to)
                .providerMessageId(result.providerMessageId())
                .status(result.ok() ? CrmEnums.MessageStatus.SENT : CrmEnums.MessageStatus.FAILED)
                .build());
        // Orders placed from this link are credited to this message
        if (orderLink != null) linkService.attachMessage(orderLink.link().getId(), saved.getId());
        return saved;
    }

    /** Whether this shop is in the marketing hold-out group (stable per distributor + shop). */
    public boolean isHoldout(Long distributorId, Long shopId) {
        if (holdoutPercent <= 0) return false;
        int bucket = Math.floorMod(Objects.hash(distributorId, shopId, "crm-holdout"), 100);
        return bucket < holdoutPercent;
    }

    // ───────────────────────────── internals ─────────────────────────────

    private String skipReason(CrmContact contact, CrmTemplate template, Long distributorId, Long shopId) {
        if (contact.getPhone() == null && contact.getWhatsappNumber() == null) {
            return "No valid phone number";
        }
        if (!template.isMarketing()) {
            return null;
        }
        if (Boolean.TRUE.equals(contact.getMarketingOptOut())) {
            return "Shop opted out (STOP)";
        }
        if (isHoldout(distributorId, shopId)) {
            return "Hold-out group (measures uplift)";
        }
        if (inQuietHours(LocalTime.now())) {
            return "Quiet hours (" + quietStart + "-" + quietEnd + ")";
        }
        long sentThisWeek = messageRepository.countMarketingSince(distributorId, shopId,
                CrmEnums.MessageStatus.SENT, LocalDateTime.now().minusDays(7));
        if (sentThisWeek >= maxMarketingPerWeek) {
            return "Weekly limit reached (" + maxMarketingPerWeek + " marketing messages)";
        }
        return null;
    }

    boolean inQuietHours(LocalTime now) {
        LocalTime start = LocalTime.parse(quietStart);
        LocalTime end = LocalTime.parse(quietEnd);
        return start.isAfter(end)
                ? !now.isBefore(start) || now.isBefore(end)   // window wraps midnight, e.g. 19:00-08:00
                : !now.isBefore(start) && now.isBefore(end);
    }

    /**
     * WhatsApp when the shop prefers it and either the message has an approved WhatsApp template or
     * the shop wrote to us in the last 24 hours (free text is only allowed then). Otherwise SMS.
     */
    private MessageChannelSender pickSender(CrmContact contact, CrmTemplate template) {
        MessageChannelSender sms = byChannel(CrmEnums.Channel.SMS);
        if (contact.getPreferredChannel() == CrmEnums.Channel.WHATSAPP && template != CrmTemplate.WHATSAPP_INVITE) {
            MessageChannelSender wa = byChannel(CrmEnums.Channel.WHATSAPP);
            boolean inWindow = contact.getLastWhatsappInboundAt() != null
                    && contact.getLastWhatsappInboundAt().isAfter(LocalDateTime.now().minusHours(23));
            if (wa != null && wa.isEnabled()
                    && (WhatsAppTemplates.forTemplate(template).isPresent() || inWindow)) {
                return wa;
            }
        }
        if (sms == null) throw new IllegalStateException("No SMS sender configured");
        return sms;
    }

    private MessageChannelSender byChannel(CrmEnums.Channel channel) {
        return senders.stream().filter(s -> s.channel() == channel).findFirst().orElse(null);
    }

    private String merchantName(Long merchantId) {
        return merchantService.getMerchantById(merchantId).map(Merchant::getBusinessName).orElse("");
    }

    /** "MAMA MBOGA LIQUOR STORE LTD" → "Mama Mboga Liquor Store Ltd", trimmed to keep SMS short. */
    private static String shortName(String name) {
        if (name == null || name.isBlank()) return "";
        String trimmed = name.trim();
        if (trimmed.equals(trimmed.toUpperCase())) {
            StringBuilder sb = new StringBuilder();
            for (String w : trimmed.toLowerCase().split("\\s+")) {
                if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
            trimmed = sb.toString().trim();
        }
        return trimmed.length() > 30 ? trimmed.substring(0, 30).trim() : trimmed;
    }

    /** The SMS client builds JSON by hand, so strip characters that would break it. */
    private static String smsSafe(String text) {
        return text == null ? "" : text.replaceAll("[\\\\\"\\r\\n\\t]", " ").replaceAll(" {2,}", " ").trim();
    }
}
