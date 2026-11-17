package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Item;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Shop;
import com.dayworks_ltd.loyalty_engine.smm.comments.ReplyWriter;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the chat going after a buyer answers a private reply. Every buyer message gets one answer that moves towards
 * an order: which item, which colour or size, then the order link, or for a shop visit which item to keep aside.
 * Haggling, bulk orders, complaints and anything the facts don't cover go to the seller, and the agents then stay
 * quiet in that chat. Nothing is sent in shadow mode, while paused, or after Meta's 24-hour window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmConversationAgent {

    /** Meta lets a Page answer for 24 hours after the buyer's last message. */
    static final Duration WINDOW = Duration.ofHours(24);
    /** After this many agent messages in one chat a person takes over. */
    static final int MAX_AGENT_TURNS = 8;

    @Value("${smm.chat.enabled:true}")
    private boolean enabled = true;
    @Value("${smm.chat.model:claude-sonnet-5-5}")
    private String model = "claude-sonnet-5-5";

    private final SmmChatMessageRepository chat;
    private final SmmReplyRepository replies;
    private final SmmCommentRepository comments;
    private final SmmAccountRepository accounts;
    private final SmmSellerProfileRepository profiles;
    private final SmmHandoverRepository handovers;
    private final MetaGraphClient graph;
    private final ShoppingListReader claude;
    private final SmmCommentWorker worker;
    private final SmmLeadRepository leads;
    private final SmmLeadAlertService leadAlerts;

    /** Marks the one message sent after a handover when the buyer asks again. */
    static final String HOLDING = "HOLDING";
    private static final Pattern NAMES_SOMEONE_ELSE = Pattern.compile("(?i)\\b(owner|manager|boss|mwenye duka|mwenyewe)\\b");

    record Turn(
            @JsonPropertyDescription("The message to send to the buyer")
            String reply,
            @JsonPropertyDescription("True when the seller must take over: haggling or a discount, bulk or wholesale, a complaint, a return, or a question the facts don't answer")
            boolean handover,
            @JsonPropertyDescription("When handover is true, one of: HAGGLE, BULK, COMPLAINT, AFTER_SALES, UNSURE; otherwise null")
            String reason) {}

    /** A buyer's message that followed one of our private replies. Stored once; answered by {@link #run}. */
    public void record(SmmReply opener, String buyerId, String messageId, String text, LocalDateTime at) {
        if (messageId != null && chat.existsByMetaMessageId(messageId)) return;
        chat.save(SmmChatMessage.builder().merchantId(opener.getMerchantId()).replyId(opener.getId())
                .commentId(opener.getCommentId()).buyerId(buyerId).fromBuyer(true)
                .text(text == null ? null : text.length() > 2000 ? text.substring(0, 2000) : text)
                .metaMessageId(messageId).status(SmmChatMessage.Status.RECEIVED).createdAt(at).build());
    }

    @Scheduled(fixedDelayString = "${smm.chat-delay-ms:5000}", initialDelay = 30000)
    public void run() {
        Map<Long, SmmChatMessage> latest = new LinkedHashMap<>();
        List<SmmChatMessage> waiting = chat.findTop40ByStatusOrderByIdAsc(SmmChatMessage.Status.RECEIVED);
        waiting.forEach(m -> latest.put(m.getReplyId(), m)); // several messages in a row get one answer
        for (SmmChatMessage last : latest.values()) {
            String note = null;
            try {
                note = answer(last);
            } catch (Exception e) {
                note = "Error: " + MetaGraphClient.redact(String.valueOf(e.getMessage()));
                log.warn("SokoReply chat: could not answer buyer message {}: {}", last.getId(), note);
            }
            for (SmmChatMessage m : waiting) {
                if (!m.getReplyId().equals(last.getReplyId())) continue;
                m.setStatus(SmmChatMessage.Status.ANSWERED);
                if (note != null) m.setNote(note.length() > 300 ? note.substring(0, 300) : note);
            }
        }
        chat.saveAll(waiting);
    }

    /** Sends the answer. Returns why nothing was sent, or null when a message went out. */
    String answer(SmmChatMessage last) {
        if (!enabled) return "Chat agent off";
        if (!claude.isConfigured()) return "Model not configured";
        SmmReply opener = replies.findById(last.getReplyId()).orElse(null);
        SmmComment comment = opener == null ? null : comments.findById(opener.getCommentId()).orElse(null);
        if (comment == null) return "Comment not found";
        SmmSellerProfile profile = profiles.findByMerchantId(last.getMerchantId()).orElse(null);
        if (profile == null || profile.isShadowMode()) return "Shadow mode";
        if (profile.isAgentsPaused()) return "Agents paused";
        SmmAccount account = accounts.findById(comment.getAccountId()).orElse(null);
        if (account == null || account.getStatus() != SmmAccount.Status.ACTIVE || account.getAccessToken() == null) {
            return "Account not connected";
        }
        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        if (last.getCreatedAt().plus(WINDOW).isBefore(now)) return "More than 24 hours after the buyer's message";

        List<SmmChatMessage> thread = chat.findByReplyIdOrderByIdAsc(last.getReplyId());
        boolean sw = "sw".equalsIgnoreCase(comment.getLanguage());
        if (thread.stream().anyMatch(SmmChatMessage::isHandover)) {
            // The seller has this chat. A buyer who asks again is told once that we are on it, and the seller is
            // alerted again; after that the agents stay quiet so the buyer is not fed the same line over and over.
            if (thread.stream().anyMatch(m -> !m.isFromBuyer() && HOLDING.equals(m.getNote()))) return "With the seller";
            MetaGraphClient.SentMessage held = graph.sendMessage(last.getBuyerId(), holdingLine(sw), account.getAccessToken());
            chat.save(SmmChatMessage.builder().merchantId(last.getMerchantId()).replyId(last.getReplyId())
                    .commentId(comment.getId()).buyerId(last.getBuyerId()).fromBuyer(false).text(holdingLine(sw))
                    .metaMessageId(held.messageId()).status(SmmChatMessage.Status.SENT).note(HOLDING).createdAt(now).build());
            alertSeller(comment, last);
            log.info("SokoReply chat: buyer asked again in chat {} (merchant {}), holding line sent and seller alerted again",
                    last.getReplyId(), last.getMerchantId());
            return null;
        }
        long agentTurns = thread.stream().filter(m -> !m.isFromBuyer() && m.getStatus() == SmmChatMessage.Status.SENT).count();

        SmmCommentWorker.ChatFacts facts = worker.chatFacts(comment, profile, account);
        String factSheet = factSheet(facts.shop(), facts.items(), facts.voice());

        String text;
        boolean handover;
        String reason;
        if (agentTurns >= MAX_AGENT_TURNS) {
            text = handoverLine(sw);
            handover = true;
            reason = "UNSURE";
        } else {
            Turn turn = claude.readText(system(facts.voice(), factSheet), input(comment, opener, thread), Turn.class, model);
            List<String> problems = turn == null ? List.of("no answer") : problems(turn.reply(), factSheet, threadText(comment, opener, thread),
                    facts.voice().noGoPhrases());
            if (!problems.isEmpty()) {
                // Never send something unchecked: the buyer is told a person is coming, and the seller is
                log.info("SokoReply chat: handing chat {} to the seller, the draft failed: {}", last.getReplyId(), problems);
                text = handoverLine(sw);
                handover = true;
                reason = "UNSURE";
            } else {
                handover = turn.handover();
                reason = turn.reason();
                // A handover speaks as the shop and asks nothing: no "the owner will answer", no extra question
                text = handover && (turn.reply().contains("?") || NAMES_SOMEONE_ELSE.matcher(turn.reply()).find())
                        ? handoverLine(sw) : SmmCommentWorker.plain(turn.reply());
            }
        }

        MetaGraphClient.SentMessage sent = graph.sendMessage(last.getBuyerId(), text, account.getAccessToken());
        chat.save(SmmChatMessage.builder().merchantId(last.getMerchantId()).replyId(last.getReplyId())
                .commentId(comment.getId()).buyerId(last.getBuyerId()).fromBuyer(false).text(text)
                .metaMessageId(sent.messageId()).status(SmmChatMessage.Status.SENT).handover(handover)
                .note(handover ? reason : null).createdAt(now).build());
        if (handover) {
            handovers.save(SmmHandover.builder().merchantId(last.getMerchantId()).commentId(comment.getId())
                    .reason(reason(reason)).urgent(false).build());
            alertSeller(comment, last);
        }
        log.info("SokoReply chat: answered buyer in chat {} (merchant {}){}", last.getReplyId(), last.getMerchantId(),
                handover ? ", handed to the seller: " + reason : "");
        return null;
    }

    // ───────────────────────────── checks ─────────────────────────────

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]]+");
    private static final Pattern BOT = Pattern.compile("(?i)\\b(bot|robot|chatbot|artificial intelligence)\\b|\\bAI\\b");

    /** Why a draft can't be sent: every link and number must come from the facts or the chat itself. */
    static List<String> problems(String reply, String factSheet, String threadText, String noGoPhrases) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) return List.of("empty reply");
        if (reply.length() > 700) out.add("too long");
        Matcher m = URL.matcher(reply);
        while (m.find()) {
            String url = m.group().replaceAll("[.,!?]+$", "");
            if (!factSheet.contains(url)) out.add("link not in the facts");
        }
        Set<String> known = ReplyWriter.numbers(factSheet + "\n" + threadText);
        for (String n : ReplyWriter.numbers(URL.matcher(reply).replaceAll(" "))) {
            if (!known.contains(n)) out.add("number not in the facts: " + n);
        }
        if (BOT.matcher(reply).find()) out.add("says bot or AI");
        if (noGoPhrases != null) {
            for (String phrase : noGoPhrases.split("[\\n,;]")) {
                String p = phrase.trim().toLowerCase(Locale.ROOT);
                if (p.length() > 2 && reply.toLowerCase(Locale.ROOT).contains(p)) out.add("uses a no-go phrase");
            }
        }
        return out;
    }

    static String handoverLine(boolean sw) {
        return sw ? "Wacha nikuthibitishie hilo, nitakujibu hapa hapa hivi punde."
                : "Let me confirm that for you and get back to you right here shortly.";
    }

    static String holdingLine(boolean sw) {
        return sw ? "Bado nakuthibitishia, sijakusahau. Nitakujibu hapa mara tu nikipata jibu."
                : "Still confirming that for you, I haven't forgotten. I'll reply right here as soon as I have it.";
    }

    /** The buyer is waiting on the seller: put them at the top of the seller's leads and send the hot-lead alert. */
    private void alertSeller(SmmComment comment, SmmChatMessage last) {
        try {
            Lead.Platform platform = comment.getPlatform() == SocialPlatform.INSTAGRAM ? Lead.Platform.INSTAGRAM : Lead.Platform.FACEBOOK;
            leads.findFirstByMerchantIdAndSenderPsidAndPlatform(last.getMerchantId(), last.getBuyerId(), platform).ifPresent(lead -> {
                lead.setTemperature(Lead.Temperature.HOT);
                lead.setHotAlertPending(true);
                String want = last.getText() == null ? "waiting for your answer in Messenger" : "asks in Messenger: " + last.getText().replace('\n', ' ');
                lead.setHotWant(want.length() > 120 ? want.substring(0, 120) : want);
                leads.save(lead);
            });
            leadAlerts.maybeAlert(last.getMerchantId());
        } catch (Exception e) {
            log.warn("SokoReply chat: could not alert the seller for chat {}: {}", last.getReplyId(), e.getMessage());
        }
    }

    static SmmHandover.Reason reason(String r) {
        try {
            SmmHandover.Reason parsed = SmmHandover.Reason.valueOf(String.valueOf(r).trim().toUpperCase(Locale.ROOT));
            return switch (parsed) {
                case HAGGLE, BULK, COMPLAINT, AFTER_SALES -> parsed;
                default -> SmmHandover.Reason.UNSURE;
            };
        } catch (IllegalArgumentException e) {
            return SmmHandover.Reason.UNSURE;
        }
    }

    // ───────────────────────────── prompts ─────────────────────────────

    static String factSheet(Shop shop, List<Item> items, ReplyWriter.Voice voice) {
        StringBuilder sb = new StringBuilder();
        if (voice.shopFacts() != null) sb.append(voice.shopFacts()).append('\n');
        if (items.isEmpty()) {
            sb.append("No catalogue items are tagged on this post. A price or size is known only if the seller's caption above or the seller's answers below give it.\n");
        } else {
            sb.append("Items on this post:\n");
            for (Item i : items) {
                sb.append("- ").append(i.name());
                if (i.colour() != null) sb.append(", colour ").append(i.colour());
                if (i.size() != null) sb.append(", size ").append(i.size());
                if (i.price() != null) sb.append(", KES ").append(i.price().stripTrailingZeros().toPlainString());
                if (i.stock() != null) sb.append(i.stock() > 0 ? ", in stock" : ", OUT of stock");
                sb.append('\n');
            }
        }
        if (shop.orderLink() != null) sb.append("Order link (order and pay the shop's M-Pesa till): ").append(shop.orderLink()).append('\n');
        if (voice.faq() != null && !voice.faq().isEmpty()) {
            sb.append("The seller's own answers to earlier questions:\n");
            voice.faq().forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }

    static String system(ReplyWriter.Voice v, String factSheet) {
        StringBuilder sb = new StringBuilder("""
                You are a sales agent for %s, a Kenyan business. A buyer commented on one of its posts, got a private message from the shop, and is now chatting in Messenger. Write the shop's next message.

                Your goal is to help the seller close the sale: an order, a reserved slot, or a phone number the seller can call \
                to finish it themselves. "The comment got answered" is never the finish line on its own.

                One small step at a time:
                1. If you don't yet know which item they want, ask which one (or ask for a photo or the name).
                2. If the item has colours or sizes and they haven't chosen, ask for that.
                3. Once the item is clear, or they ask how to order or pay, give the order link from the facts. Send the link at most once in the chat.
                4. If they say they will visit: confirm the location and opening hours from the facts, and ask which item to keep aside for them and roughly what time they will come. Offer delivery through the order link only if a link is in the facts.
                5. If the item and what they want are both settled (colour/size chosen, or they've said they'll visit or use the link) and you have not already asked for or been given a phone number in this chat: ask for it once, framed as helping them — so the seller can confirm it's ready, hold their slot, or reach them if anything changes. Never say it's needed to get the price or to continue the chat; the buyer can decline and you move on.
                6. If they have ordered, paid, given a number, or only said thanks or OK with nothing left to settle: thank them warmly in one line and ask nothing.

                Rules:
                - Answer ONLY what the buyer just said, then take the next step. Do not add the location, opening hours, product details or a link they did not ask for, and do not offer two options when they chose one.
                - A buyer whose budget is below the price is not rude and not bargaining. Look at "Other items this seller has posted" in the facts: if one is at or under their budget, name it and its price and ask if they'd like that one instead. If none fits, say plainly what this item costs and that you are checking what else might fit their budget, and set handover.
                - Use only the facts below and what was said in this chat. Never invent a price, discount, stock claim, delivery fee, delivery time, phone number or link. If the facts do not answer the question, set handover.
                - The caption the seller wrote on the post, when it is in the facts, describes the item in the post: its prices, sizes and details are facts you may use. "The one on the post" means that item.
                - Set handover for: bargaining or a discount, bulk or wholesale, a complaint, a return or exchange, anything rude, or anything you cannot answer from the facts. When handing over, say only that you are confirming it and will get back to them right here shortly. You are the shop: never mention an owner, a manager or anyone else, and ask no question.
                - One or two short sentences, under 30 words in all. End with exactly one easy question, except in case 6 or when handing over.
                - Write in the buyer's language: Swahili (light Sheng if they use it) or English.
                - No emojis. Never say you are a bot or AI. Never sign with a person's name.
                - Do not repeat anything the shop already said in this chat.
                """.formatted(v.shopName() == null ? "the shop" : v.shopName()));
        if (v.tone() != null && !v.tone().isBlank()) sb.append("\nThe seller's tone: ").append(v.tone().trim()).append('\n');
        if (v.noGoPhrases() != null && !v.noGoPhrases().isBlank()) {
            sb.append("\nNever use these words or promises:\n").append(v.noGoPhrases().trim()).append('\n');
        }
        if (v.voiceSamples() != null && !v.voiceSamples().isBlank()) {
            sb.append("\nHow the seller writes:\n").append(v.voiceSamples().trim()).append('\n');
        }
        sb.append("\nFacts:\n").append(factSheet);
        return sb.toString();
    }

    static String input(SmmComment comment, SmmReply opener, List<SmmChatMessage> thread) {
        return "The chat so far:\n" + threadText(comment, opener, thread) + "\nWrite the shop's next message.";
    }

    static String threadText(SmmComment comment, SmmReply opener, List<SmmChatMessage> thread) {
        StringBuilder sb = new StringBuilder();
        sb.append("Buyer (public comment): ").append(oneLine(comment.getText())).append('\n');
        sb.append("Shop (private message): ").append(oneLine(opener.getText())).append('\n');
        for (SmmChatMessage m : thread) {
            if (!m.isFromBuyer() && m.getStatus() != SmmChatMessage.Status.SENT) continue;
            sb.append(m.isFromBuyer() ? "Buyer: " : "Shop: ").append(oneLine(m.getText())).append('\n');
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "[photo or attachment]" : s.replace('\n', ' ').strip();
    }
}
