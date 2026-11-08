package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.model.Lead;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmReply;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmLeadRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmReplyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Listens for buyers' Messenger and Instagram messages and marks the private reply they are answering. Meta allows
 * one private reply per comment, so whether the buyer wrote back is the number that tells us the reply worked (the
 * reply rate). Only the matching happens here; the agents do not answer these messages yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmMessageTracker {

    /** An answer counts for the private reply sent in the last 7 days (the same window Meta gives for sending it). */
    static final Duration ANSWER_WINDOW = Duration.ofDays(7);

    private final SmmAccountRepository accounts;
    private final SmmReplyRepository replies;
    private final SmmLeadRepository leads;

    /** One message from a buyer, pulled out of a webhook. accountId = the Page or Instagram account it was sent to. */
    public record BuyerMessage(SocialPlatform platform, String accountId, String senderId, String messageId, String text, LocalDateTime at) {}

    /** How many private replies were marked answered for the first time. */
    @Transactional
    public int track(Map<String, Object> payload) {
        int answered = 0;
        for (BuyerMessage m : parse(payload)) {
            if (mark(m)) answered++;
        }
        return answered;
    }

    /** Buyer messages in a Messenger ("page") or Instagram webhook. The shop's own messages (echoes) are skipped. */
    @SuppressWarnings("unchecked")
    static List<BuyerMessage> parse(Map<String, Object> payload) {
        String object = String.valueOf(payload.get("object"));
        SocialPlatform platform = "instagram".equals(object) ? SocialPlatform.INSTAGRAM : "page".equals(object) ? SocialPlatform.FACEBOOK : null;
        if (platform == null) return List.of();
        List<BuyerMessage> out = new ArrayList<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) payload.getOrDefault("entry", List.of())) {
            String accountId = str(entry.get("id"));
            for (Map<String, Object> ev : (List<Map<String, Object>>) entry.getOrDefault("messaging", List.of())) {
                Map<String, Object> sender = (Map<String, Object>) ev.getOrDefault("sender", Map.of());
                Map<String, Object> message = (Map<String, Object>) ev.get("message");
                String senderId = str(sender.get("id"));
                if (message == null || senderId == null || accountId == null) continue; // reads, deliveries, reactions
                if (Boolean.TRUE.equals(message.get("is_echo")) || senderId.equals(accountId)) continue; // the shop's own
                String text = str(message.get("text"));
                if (text == null && message.get("attachments") != null) text = "[photo or attachment]";
                out.add(new BuyerMessage(platform, accountId, senderId, str(message.get("mid")), text, millis(ev.get("timestamp"))));
            }
        }
        return out;
    }

    private boolean mark(BuyerMessage m) {
        Optional<SmmAccount> account = accounts.findByPlatformAndPageId(m.platform(), m.accountId());
        if (account.isEmpty()) return false;
        Long merchantId = account.get().getMerchantId();
        LocalDateTime at = m.at() == null ? LocalDateTime.now(SmmIntakeService.NAIROBI) : m.at();

        Optional<SmmReply> reply = replies.findFirstByMerchantIdAndKindAndStatusAndRecipientIdAndSentAtAfterOrderBySentAtDesc(
                merchantId, SmmReply.Kind.PRIVATE, SmmReply.Status.SENT, m.senderId(), at.minus(ANSWER_WINDOW));
        if (reply.isEmpty()) return false; // a message that did not follow one of our private replies
        boolean first = record(reply.get(), m.text(), at);
        replies.save(reply.get());

        // A buyer who answers the private reply is ready to talk: the seller should see them at the top
        Lead.Platform lp = m.platform() == SocialPlatform.INSTAGRAM ? Lead.Platform.INSTAGRAM : Lead.Platform.FACEBOOK;
        leads.findFirstByMerchantIdAndSenderPsidAndPlatform(merchantId, m.senderId(), lp).ifPresent(lead -> {
            lead.setTemperature(Lead.Temperature.HOT);
            lead.setLatestMessage(m.text());
            lead.setLastContactAt(at);
            lead.setMessageCount((lead.getMessageCount() == null ? 0 : lead.getMessageCount()) + 1);
            leads.save(lead);
        });
        if (first) log.info("SokoReply: buyer answered private reply {} (merchant {})", reply.get().getId(), merchantId);
        return first;
    }

    /** Marks the reply answered. True the first time the buyer writes back; later messages only move the chat window. */
    static boolean record(SmmReply reply, String text, LocalDateTime at) {
        boolean first = reply.getBuyerRepliedAt() == null;
        if (first) {
            reply.setBuyerRepliedAt(at);
            reply.setBuyerReplyText(text == null ? null : text.length() > 1000 ? text.substring(0, 1000) : text);
        }
        if (reply.getLastBuyerMessageAt() == null || at.isAfter(reply.getLastBuyerMessageAt())) reply.setLastBuyerMessageAt(at);
        reply.setBuyerMessageCount((reply.getBuyerMessageCount() == null ? 0 : reply.getBuyerMessageCount()) + 1);
        return first;
    }

    /** The reply rate for the portal and Phase 6: of buyers who got a private reply, how many answered or ordered. */
    public record ReplyRate(int sent, int answered, int ordered, int answeredOrOrdered, Double rate) {
        public static ReplyRate of(List<SmmReply> sent, java.util.Set<Long> orderedCommentIds) {
            int answered = 0, ordered = 0, either = 0;
            for (SmmReply r : sent) {
                boolean a = r.getBuyerRepliedAt() != null, o = orderedCommentIds.contains(r.getCommentId());
                if (a) answered++;
                if (o) ordered++;
                if (a || o) either++;
            }
            return new ReplyRate(sent.size(), answered, ordered, either,
                    sent.isEmpty() ? null : Math.round(1000.0 * either / sent.size()) / 10.0);
        }
    }

    private static LocalDateTime millis(Object value) {
        if (value instanceof Number n) return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), SmmIntakeService.NAIROBI);
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
