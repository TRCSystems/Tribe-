package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 7:30 every morning, a WhatsApp to each seller with yesterday in numbers, and three buttons: answer the questions
 * the agents handed over, check the agents' replies (👍 or ✏️, the one-tap fix), or pause the agents. The buttons
 * open {@link SmmSellerCommands}. Sellers with no comments in the last 24 hours get nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmMorningSummary {

    public static final String TEMPLATE = "sokoreply_morning_summary";

    private final SmmSellerProfileRepository profiles;
    private final SmmAccountRepository accounts;
    private final SmmCommentRepository comments;
    private final SmmReplyRepository replies;
    private final SmmLeadRepository leads;
    private final SmmHandoverRepository handovers;
    private final WhatsAppCloudClient whatsapp;
    private final WhatsAppProperties whatsappProps;

    /** Yesterday in numbers, for one seller. */
    record Numbers(long comments, long buyers, long repliesSent, long buyersWroteBack, long hotLeads, long hidden, long questions) {}

    @Scheduled(cron = "${smm.summary-cron:0 30 7 * * *}", zone = "Africa/Nairobi")
    public void run() {
        if (!whatsappProps.canSend()) return;
        for (SmmSellerProfile p : profiles.findAll()) {
            try {
                send(p);
            } catch (Exception e) {
                log.warn("SokoReply morning summary for merchant {} failed: {}", p.getMerchantId(), e.getMessage());
            }
        }
    }

    /** Sends one seller's summary; false when there is nothing to send or no number to send it to. */
    boolean send(SmmSellerProfile p) {
        if (p.getWhatsappNumber() == null || p.getWhatsappNumber().isBlank()) return false;
        List<SmmAccount> pages = accounts.findByMerchantId(p.getMerchantId()).stream()
                .filter(a -> a.getStatus() == SmmAccount.Status.ACTIVE).toList();
        if (pages.isEmpty()) return false;
        Numbers n = numbers(p.getMerchantId(), LocalDateTime.now(SmmIntakeService.NAIROBI));
        if (n.comments() == 0) return false;
        String where = String.join(" and ", pages.stream().map(a -> a.getPageName() == null ? "your Page" : a.getPageName())
                .distinct().toList());
        WhatsAppCloudClient.Sent sent = whatsapp.sendTemplateWithReplies(p.getWhatsappNumber(), TEMPLATE, params(where, n),
                List.of("SMM_QUESTIONS", "SMM_REPLIES", "SMM_PAUSE"));
        if (!sent.ok()) {
            log.warn("SokoReply morning summary for merchant {} failed: {}", p.getMerchantId(), sent.error());
            return false;
        }
        log.info("SokoReply: morning summary sent to merchant {} ({} comments)", p.getMerchantId(), n.comments());
        return true;
    }

    Numbers numbers(Long m, LocalDateTime now) {
        LocalDateTime since = now.minusHours(24);
        List<SmmReply.Kind> answers = List.of(SmmReply.Kind.PUBLIC, SmmReply.Kind.PRIVATE);
        long questions = handovers.findByMerchantIdAndUrgentFalseAndAnsweredAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(m, now.minus(SmmSellerCommands.REVIEW_BACK))
                .stream().filter(h -> h.getReason() != SmmHandover.Reason.NEGATIVE && h.getReason() != SmmHandover.Reason.COMPLAINT).count();
        return new Numbers(
                comments.countByMerchantIdAndReceivedAtAfter(m, since),
                comments.countByMerchantIdAndBuyerTrueAndReceivedAtAfter(m, since),
                replies.countByMerchantIdAndStatusAndKindInAndSentAtAfter(m, SmmReply.Status.SENT, answers, since),
                replies.countByMerchantIdAndKindAndBuyerRepliedAtAfter(m, SmmReply.Kind.PRIVATE, since),
                leads.countByMerchantIdAndTemperatureAndLastContactAtAfter(m, Lead.Temperature.HOT, since),
                replies.countByMerchantIdAndStatusAndKindInAndSentAtAfter(m, SmmReply.Status.SENT, List.of(SmmReply.Kind.HIDE), since),
                questions);
    }

    /** {{1}} the Page, then the seven numbers in the order the template reads them. */
    static List<String> params(String where, Numbers n) {
        return List.of(where, String.valueOf(n.comments()), String.valueOf(n.buyers()), String.valueOf(n.repliesSent()),
                String.valueOf(n.buyersWroteBack()), String.valueOf(n.hotLeads()), String.valueOf(n.hidden()),
                String.valueOf(n.questions()));
    }

    /** The template to register with Meta (utility), with its three quick-reply buttons. */
    public static Map<String, Object> templateDefinition(String language) {
        String text = "☀️ Good morning from Tribe. In the last 24 hours on {{1}}: {{2}} comments, {{3}} of them from buyers. "
                + "Our agents sent {{4}} replies and {{5}} buyers wrote back. Hot leads: {{6}}. Bad comments hidden: {{7}}. "
                + "Questions waiting for your answer: {{8}}. Tap a button below to answer them, check our replies, or pause the agents.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", text);
        body.put("example", Map.of("body_text", List.of(List.of("ASANO GROUP", "42", "30", "55", "12", "6", "2", "3"))));
        Map<String, Object> buttons = Map.of("type", "BUTTONS", "buttons", List.of(
                Map.of("type", "QUICK_REPLY", "text", "Answer questions"),
                Map.of("type", "QUICK_REPLY", "text", "Check replies"),
                Map.of("type", "QUICK_REPLY", "text", "Pause agents")));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", TEMPLATE);
        def.put("language", language);
        def.put("category", "UTILITY");
        def.put("components", List.of(body, buttons));
        return def;
    }
}
