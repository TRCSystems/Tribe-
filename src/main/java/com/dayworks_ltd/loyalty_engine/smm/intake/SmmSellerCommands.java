package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient.Button;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * What a seller can do from WhatsApp, by typing or by tapping the buttons on the agents' alerts and morning summary:
 * <ul>
 *   <li>PAUSE / SIMAMISHA and RESUME / ENDELEA: stop or restart the agents' replies (bad comments and phone numbers
 *       are still hidden while paused, since that says nothing in the seller's name).</li>
 *   <li>Called ✅ on a hot-lead alert: those buyers are marked contacted.</li>
 *   <li>Answer questions: the questions the agents handed over, one at a time. The seller types the answer; it is kept
 *       as an FAQ the agents use from then on (seller memory) and goes to the buyer if their chat is still open.</li>
 *   <li>Check replies: yesterday's replies one at a time, 👍 Good or ✏️ Fix. A fix is the seller's own wording and
 *       joins their voice samples, so the Writer sounds more like them.</li>
 * </ul>
 * Only numbers that are a seller's WhatsApp number in SokoReply are handled here; everyone else goes on to the
 * usual WhatsApp routing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmSellerCommands {

    static final Set<String> PAUSE = Set.of("PAUSE", "SIMAMISHA", "PAUSE AGENTS", "SMM_PAUSE");
    static final Set<String> RESUME = Set.of("RESUME", "ENDELEA", "RESUME AGENTS", "SMM_RESUME");
    /** How long the seller has to answer a question or fix a reply (WhatsApp's own chat window). */
    static final Duration ANSWER_WINDOW = Duration.ofHours(24);
    /** Questions and replies older than this are not brought up in the review. */
    static final Duration REVIEW_BACK = Duration.ofDays(3);
    /** Voice samples kept per seller (newest last). */
    static final int MAX_SAMPLES = 30;

    private final SmmSellerProfileRepository profiles;
    private final SmmAccountRepository accounts;
    private final SmmLeadRepository leads;
    private final SmmHandoverRepository handovers;
    private final SmmCommentRepository comments;
    private final SmmReplyRepository replies;
    private final SmmFaqRepository faqs;
    private final WhatsAppCloudClient whatsapp;
    private final MetaGraphClient graph;

    /** True when the message was a seller's and has been dealt with here. */
    public boolean handle(String from, String type, String content) {
        String wa = WhatsAppCloudClient.toWaNumber(from);
        if (wa == null) return false;
        List<SmmSellerProfile> sellers = profiles.findAll().stream()
                .filter(p -> wa.equals(WhatsAppCloudClient.toWaNumber(p.getWhatsappNumber()))).toList();
        if (sellers.isEmpty()) return false;

        String text = content == null ? "" : content.trim();
        String cmd = text.toUpperCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        try {
            if (PAUSE.contains(cmd)) return pause(from, sellers, true);
            if (RESUME.contains(cmd)) return pause(from, sellers, false);
            if (cmd.startsWith("SMM_CALLED:")) return called(from, sellers, text.substring(11));
            if (cmd.equals("SMM_QUESTIONS") || cmd.equals("ANSWER QUESTIONS")) return nextQuestion(from, sellers);
            if (cmd.equals("SMM_REPLIES") || cmd.equals("CHECK REPLIES")) return nextReply(from, sellers);
            if (cmd.startsWith("SMM_SKIP:")) return skip(from, sellers, parse(text.substring(9)));
            if (cmd.startsWith("SMM_GOOD:")) return good(from, sellers, parse(text.substring(9)));
            if (cmd.startsWith("SMM_FIX:")) return askFix(from, sellers, parse(text.substring(8)));
            // Free text while a question or a fix is waiting for the seller's words
            Optional<SmmSellerProfile> waiting = sellers.stream()
                    .filter(p -> p.getAwaiting() != null && p.getAwaitingSince() != null
                            && p.getAwaitingSince().isAfter(now.minus(ANSWER_WINDOW)))
                    .findFirst();
            if (waiting.isPresent() && "text".equals(type) && !text.isEmpty()) return answer(from, sellers, waiting.get(), text);
        } catch (Exception e) {
            log.warn("SokoReply seller command from merchant {} failed: {}", sellers.get(0).getMerchantId(), e.getMessage());
            whatsapp.sendText(from, "Sorry, something went wrong on our side. Please try again in a moment.");
            return true;
        }
        return false;
    }

    // ───────────────────────────── pause ─────────────────────────────

    private boolean pause(String from, List<SmmSellerProfile> sellers, boolean paused) {
        sellers.forEach(p -> p.setAgentsPaused(paused));
        profiles.saveAll(sellers);
        log.info("SokoReply: agents {} by WhatsApp for merchant(s) {}", paused ? "paused" : "resumed",
                sellers.stream().map(SmmSellerProfile::getMerchantId).toList());
        if (paused) {
            whatsapp.sendButtons(from, "⏸️ Agents paused for " + shopNames(sellers) + ". Comments are still read, and bad "
                    + "comments and phone numbers are still hidden, but no replies go out. Send RESUME to start again.",
                    List.of(new Button("SMM_RESUME", "Resume agents")));
        } else {
            whatsapp.sendButtons(from, "▶️ Agents are back on for " + shopNames(sellers) + ". New comments get answered "
                    + "again. Send PAUSE anytime to stop them.", List.of(new Button("SMM_PAUSE", "Pause agents")));
        }
        return true;
    }

    // ───────────────────────────── hot leads ─────────────────────────────

    private boolean called(String from, List<SmmSellerProfile> sellers, String ids) {
        Set<Long> mine = merchantIds(sellers);
        int n = 0;
        for (String id : ids.split(",")) {
            Long leadId = parse(id);
            if (leadId == null) continue;
            Lead lead = leads.findById(leadId).filter(l -> mine.contains(l.getMerchantId())).orElse(null);
            if (lead == null) continue;
            if (lead.getStatus() == null || lead.getStatus() == Lead.LeadStatus.NEW || lead.getStatus() == Lead.LeadStatus.HOT) {
                lead.setStatus(Lead.LeadStatus.CONTACTED);
            }
            lead.setLastContactAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
            leads.save(lead);
            n++;
        }
        whatsapp.sendText(from, n == 0 ? "Noted ✅" : "Noted ✅ Marked " + (n == 1 ? "the buyer" : n + " buyers") + " as called. Good luck with the sale!");
        return true;
    }

    // ───────────────────────────── questions (seller memory: FAQ) ─────────────────────────────

    /** The questions waiting for these sellers, oldest first; bad comments are not asked about here. */
    private List<SmmHandover> openQuestions(List<SmmSellerProfile> sellers) {
        LocalDateTime since = LocalDateTime.now(SmmIntakeService.NAIROBI).minus(REVIEW_BACK);
        List<SmmHandover> out = new ArrayList<>();
        for (SmmSellerProfile p : sellers) {
            for (SmmHandover h : handovers.findByMerchantIdAndUrgentFalseAndAnsweredAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(p.getMerchantId(), since)) {
                if (h.getReason() == SmmHandover.Reason.NEGATIVE || h.getReason() == SmmHandover.Reason.COMPLAINT) continue;
                comments.findById(h.getCommentId()).filter(c -> !Boolean.TRUE.equals(c.getNegative())).ifPresent(c -> out.add(h));
            }
        }
        return out;
    }

    private boolean nextQuestion(String from, List<SmmSellerProfile> sellers) {
        List<SmmHandover> open = openQuestions(sellers);
        if (open.isEmpty()) {
            clearAwaiting(sellers);
            whatsapp.sendButtons(from, "✅ No questions waiting for you. The agents answered everything else.",
                    List.of(new Button("SMM_REPLIES", "Check replies")));
            return true;
        }
        SmmHandover h = open.get(0);
        SmmComment c = comments.findById(h.getCommentId()).orElseThrow();
        SmmSellerProfile seller = sellerOf(sellers, h.getMerchantId());
        seller.setAwaiting("FAQ:" + h.getId());
        seller.setAwaitingSince(LocalDateTime.now(SmmIntakeService.NAIROBI));
        profiles.save(seller);
        String body = "❓ Question " + (open.size() == 1 ? "(last one)" : "1 of " + open.size()) + " · " + platform(c) + "\n"
                + "A buyer asked: “" + clip(c.getText(), 500) + "”\n\n"
                + "Our agents told them you'd answer. Type your answer here: we'll send it to the buyer if their chat is "
                + "still open, and the agents will use it every time this comes up.";
        whatsapp.sendButtons(from, body, List.of(new Button("SMM_SKIP:" + h.getId(), "Skip")));
        return true;
    }

    private boolean skip(String from, List<SmmSellerProfile> sellers, Long handoverId) {
        Optional.ofNullable(handoverId).flatMap(handovers::findById).filter(h -> merchantIds(sellers).contains(h.getMerchantId())).ifPresent(h -> {
            h.setAnsweredAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
            h.setOutcome("Skipped in the WhatsApp review");
            handovers.save(h);
        });
        clearAwaiting(sellers);
        return nextQuestion(from, sellers);
    }

    private boolean answer(String from, List<SmmSellerProfile> sellers, SmmSellerProfile seller, String text) {
        String awaiting = seller.getAwaiting();
        clearAwaiting(List.of(seller));
        if (awaiting.startsWith("FAQ:")) return answerQuestion(from, sellers, parse(awaiting.substring(4)), text);
        if (awaiting.startsWith("FIX:")) return saveFix(from, sellers, seller, parse(awaiting.substring(4)), text);
        return false;
    }

    private boolean answerQuestion(String from, List<SmmSellerProfile> sellers, Long handoverId, String answer) {
        SmmHandover h = Optional.ofNullable(handoverId).flatMap(handovers::findById).orElse(null);
        SmmComment c = h == null ? null : comments.findById(h.getCommentId()).orElse(null);
        if (h == null || c == null) return nextQuestion(from, sellers);
        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        faqs.save(SmmFaq.builder().merchantId(h.getMerchantId()).itemCode(c.getProductCode())
                .question(clip(c.getText(), 2000)).answer(clip(answer, 2000)).language(c.getLanguage()).build());
        h.setAnsweredAt(now);
        h.setOutcome("Answered on WhatsApp: " + clip(answer, 500));
        handovers.save(h);
        String delivered = sendToBuyer(c, answer, now);
        whatsapp.sendText(from, "Saved ✅ The agents will use your answer from now on. " + delivered);
        return nextQuestion(from, sellers);
    }

    /**
     * The seller's answer goes to the buyer too when Meta still allows it: the buyer wrote back to the agents' private
     * reply within the last 24 hours. Otherwise the seller is told to answer them in the inbox.
     */
    private String sendToBuyer(SmmComment c, String answer, LocalDateTime now) {
        SmmReply dm = replies.findByCommentIdOrderByCreatedAtAsc(c.getId()).stream()
                .filter(r -> r.getKind() == SmmReply.Kind.PRIVATE && r.getStatus() == SmmReply.Status.SENT && r.getRecipientId() != null)
                .findFirst().orElse(null);
        if (dm == null || dm.getLastBuyerMessageAt() == null || dm.getLastBuyerMessageAt().isBefore(now.minusHours(24))) {
            return "The buyer hasn't written back, so Meta doesn't let us message them again; you can still answer them in your Page inbox.";
        }
        SmmAccount account = accounts.findById(c.getAccountId()).orElse(null);
        if (account == null || account.getStatus() != SmmAccount.Status.ACTIVE || account.getAccessToken() == null) {
            return "We couldn't reach the buyer because the Page isn't connected; please answer them in your Page inbox.";
        }
        try {
            graph.sendMessage(dm.getRecipientId(), answer, account.getAccessToken());
            log.info("SokoReply: seller's answer sent to the buyer of comment {} (merchant {})", c.getId(), c.getMerchantId());
            return "We've also sent it to the buyer.";
        } catch (Exception e) {
            log.warn("SokoReply: seller's answer to comment {} not delivered: {}", c.getId(), MetaGraphClient.redact(e.getMessage()));
            return "We couldn't deliver it to the buyer; please answer them in your Page inbox.";
        }
    }

    // ───────────────────────────── replies review (seller memory: voice) ─────────────────────────────

    private List<SmmReply> unreviewed(List<SmmSellerProfile> sellers) {
        LocalDateTime since = LocalDateTime.now(SmmIntakeService.NAIROBI).minus(REVIEW_BACK);
        List<SmmReply> out = new ArrayList<>();
        for (SmmSellerProfile p : sellers) {
            out.addAll(replies.findTop10ByMerchantIdAndStatusAndKindInAndSentAtAfterAndSellerReviewedAtIsNullOrderByIdAsc(
                    p.getMerchantId(), SmmReply.Status.SENT, List.of(SmmReply.Kind.PUBLIC, SmmReply.Kind.PRIVATE), since));
        }
        return out;
    }

    private boolean nextReply(String from, List<SmmSellerProfile> sellers) {
        List<SmmReply> open = unreviewed(sellers);
        if (open.isEmpty()) {
            whatsapp.sendText(from, "✅ You've seen all the recent replies. Thank you! Your fixes teach the agents to write like you.");
            return true;
        }
        SmmReply r = open.get(0);
        String comment = comments.findById(r.getCommentId()).map(SmmComment::getText).orElse("");
        String body = "💬 Reply check · " + open.size() + (open.size() == 1 ? " left" : " to go") + "\n"
                + "Buyer: “" + clip(comment, 250) + "”\n"
                + (r.getKind() == SmmReply.Kind.PUBLIC ? "Our public reply:" : "Our private message:") + "\n\n"
                + clip(r.getText(), 600);
        whatsapp.sendButtons(from, body, List.of(new Button("SMM_GOOD:" + r.getId(), "👍 Good"), new Button("SMM_FIX:" + r.getId(), "✏️ Fix")));
        return true;
    }

    private boolean good(String from, List<SmmSellerProfile> sellers, Long replyId) {
        Optional.ofNullable(replyId).flatMap(replies::findById).filter(r -> merchantIds(sellers).contains(r.getMerchantId())).ifPresent(r -> {
            r.setSellerReviewedAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
            replies.save(r);
        });
        return nextReply(from, sellers);
    }

    private boolean askFix(String from, List<SmmSellerProfile> sellers, Long replyId) {
        SmmReply r = Optional.ofNullable(replyId).flatMap(replies::findById).filter(x -> merchantIds(sellers).contains(x.getMerchantId())).orElse(null);
        if (r == null) return nextReply(from, sellers);
        SmmSellerProfile seller = sellerOf(sellers, r.getMerchantId());
        seller.setAwaiting("FIX:" + r.getId());
        seller.setAwaitingSince(LocalDateTime.now(SmmIntakeService.NAIROBI));
        profiles.save(seller);
        whatsapp.sendText(from, "✏️ Type how you would have said it. The agents learn your way of writing from it.");
        return true;
    }

    private boolean saveFix(String from, List<SmmSellerProfile> sellers, SmmSellerProfile seller, Long replyId, String text) {
        SmmReply r = Optional.ofNullable(replyId).flatMap(replies::findById).orElse(null);
        if (r != null) {
            r.setSellerCorrection(clip(text, 2000));
            r.setSellerReviewedAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
            replies.save(r);
        }
        SmmSellerProfile fresh = profiles.findById(seller.getId()).orElse(seller);
        fresh.setVoiceSamples(addSample(fresh.getVoiceSamples(), text));
        profiles.save(fresh);
        whatsapp.sendText(from, "Thanks, learned ✅");
        return nextReply(from, sellers);
    }

    /** The seller's own wording joins their voice samples, one per line, newest last, at most {@link #MAX_SAMPLES}. */
    static String addSample(String samples, String text) {
        List<String> lines = new ArrayList<>();
        if (samples != null) for (String l : samples.split("\\R")) if (!l.isBlank()) lines.add(l.trim());
        lines.add(text.replaceAll("\\s*\\R\\s*", " ").trim());
        while (lines.size() > MAX_SAMPLES) lines.remove(0);
        return String.join("\n", lines);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void clearAwaiting(List<SmmSellerProfile> sellers) {
        List<SmmSellerProfile> changed = new ArrayList<>();
        for (SmmSellerProfile p : sellers) {
            if (p.getAwaiting() == null) continue;
            p.setAwaiting(null);
            p.setAwaitingSince(null);
            changed.add(p);
        }
        if (!changed.isEmpty()) profiles.saveAll(changed);
    }

    private static Set<Long> merchantIds(List<SmmSellerProfile> sellers) {
        Set<Long> ids = new HashSet<>();
        sellers.forEach(p -> ids.add(p.getMerchantId()));
        return ids;
    }

    private static SmmSellerProfile sellerOf(List<SmmSellerProfile> sellers, Long merchantId) {
        return sellers.stream().filter(p -> p.getMerchantId().equals(merchantId)).findFirst().orElse(sellers.get(0));
    }

    private String shopNames(List<SmmSellerProfile> sellers) {
        List<String> names = new ArrayList<>();
        for (SmmSellerProfile p : sellers) {
            accounts.findByMerchantId(p.getMerchantId()).stream().map(SmmAccount::getPageName)
                    .filter(n -> n != null && !n.isBlank()).findFirst().ifPresent(names::add);
        }
        return names.isEmpty() ? "your shop" : String.join(" and ", names);
    }

    private static String platform(SmmComment c) {
        return c.getPlatform() == SocialPlatform.INSTAGRAM ? "Instagram" : "Facebook";
    }

    private static Long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
