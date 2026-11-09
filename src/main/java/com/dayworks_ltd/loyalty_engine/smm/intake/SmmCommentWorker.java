package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Item;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import com.dayworks_ltd.loyalty_engine.smm.comments.ReplyWriter;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Works through new comments: reads each one (two-step Reader), creates or warms up the lead, plans the move and
 * records it. Protective actions run from day one, even in shadow mode: a comment with a phone number or a harmful
 * comment is hidden, and urgent ones alert the seller. Public and private replies wait for {@link SmmReplySender}
 * when the seller is live; in shadow mode, while paused, or for old comments fetched at connect they are only logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmCommentWorker {

    private final SmmCommentRepository comments;
    private final SmmAccountRepository accounts;
    private final SmmSellerProfileRepository profiles;
    private final SmmPostItemRepository postItems;
    private final SmmReplyRepository replies;
    private final SmmHandoverRepository handovers;
    private final SmmLeadRepository leads;
    private final InventoryRepository inventory;
    private final CommentReader reader;
    private final MetaGraphClient graph;
    private final SmmAlertService alerts;
    private final ReplyWriter writer;
    private final SmmFaqRepository faqs;
    private final SmmLeadAlertService leadAlerts;

    /** A viral post is handled at most this many comments a minute; other posts go ahead meanwhile. */
    @Value("${smm.worker.per-post-per-minute:30}")
    private int perPostPerMinute = 30;
    private ViralGuard guard;

    private synchronized ViralGuard guard() {
        if (guard == null) guard = new ViralGuard(perPostPerMinute);
        return guard;
    }

    /** At most this many comments per run (every 5 seconds). */
    static final int PER_RUN = 50;

    @Scheduled(fixedDelayString = "${smm.worker-delay-ms:5000}", initialDelay = 20000)
    public void run() {
        if (!reader.isConfigured()) return;
        int handled = 0;
        java.time.Instant now = java.time.Instant.now();
        // Look further down the queue than one run handles, so a viral post at the front cannot block the others
        for (SmmComment c : comments.queue(SmmComment.State.NEW, org.springframework.data.domain.PageRequest.of(0, PER_RUN * 4))) {
            if (handled >= PER_RUN) break;
            if (!guard().take(c.getPostId(), now)) continue; // this post has had its share this minute
            handled++;
            try {
                process(c);
            } catch (Exception e) {
                log.warn("Comment {} could not be processed: {}", c.getId(), e.getMessage());
                c.setState(SmmComment.State.FAILED);
                c.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
                comments.save(c);
            }
        }
    }

    void process(SmmComment c) {
        String text = c.getText() == null ? "" : c.getText();
        boolean phone = CommentReplyPlanner.hasPhoneNumber(text);
        List<Item> items = itemsOnPost(c);

        java.time.Instant now = java.time.Instant.now();
        boolean repeat = guard().repeatFromSamePerson(c.getPostId(), c.getCommenterId(), text, now);
        CommentReader.Result result = ruleReading(text)
                .map(r -> new CommentReader.Result(r, false))
                .or(() -> guard().reuse(c.getPostId(), text, now)) // same words on this post: no second model call
                .orElseGet(() -> reader.readComment(text, products(items)));
        guard().remember(c.getPostId(), text, result, now);
        Reading reading = result.reading();
        c.applyReading(reading, phone, result.secondLook());

        boolean newHotLead = false;
        if (Boolean.TRUE.equals(c.getBuyer()) || CommentReplyPlanner.isBuyer(reading)) {
            Lead lead = upsertLead(c, reading, phone);
            c.setLeadId(lead.getId());
            newHotLead = Boolean.TRUE.equals(lead.getHotAlertPending());
        }

        SmmSellerProfile profile = profiles.findByMerchantId(c.getMerchantId())
                .orElseGet(() -> SmmSellerProfile.builder().merchantId(c.getMerchantId()).build());
        SmmAccount account = accounts.findById(c.getAccountId()).orElse(null);
        Duration age = c.getCommentCreatedAt() == null ? Duration.ZERO
                : Duration.between(c.getCommentCreatedAt(), LocalDateTime.now(SmmIntakeService.NAIROBI));
        Plan plan = CommentReplyPlanner.plan(text, reading, items, shop(profile, account), age);
        // Answered already (by the seller, or the same person said the same thing a moment ago): no second reply,
        // but a phone number or a bad comment is still hidden
        if (Boolean.TRUE.equals(c.getSellerAnswered()) || repeat) plan = alreadyAnswered(plan);

        // Replies: queued for the sender when the seller is live, otherwise only logged. The Writer puts the
        // planner's facts in the seller's voice (also in shadow mode, so the seller sees what would go out); old
        // comments fetched at connect and paused sellers keep the planner's wording, nothing is sent for them anyway.
        SmmReply.Status status = sendable(profile, c) ? SmmReply.Status.PENDING : SmmReply.Status.LOGGED;
        ReplyWriter.Result written = Boolean.TRUE.equals(c.getBackfilled()) || profile.isAgentsPaused()
                ? new ReplyWriter.Result(plan.publicReply(), plan.privateReply(), null, "planner", null)
                : writer.write(plan, writerContext(c, reading, profile, account));
        logReply(c, SmmReply.Kind.PUBLIC, written.publicReply(), status, written);
        logReply(c, SmmReply.Kind.PRIVATE, written.privateReply(), status, written);

        // Protective action, from day one and even when the seller paused the agents: hiding says nothing in their name
        if (plan.hideComment()) hide(c, account);

        if (plan.handover()) {
            handovers.save(SmmHandover.builder().merchantId(c.getMerchantId()).commentId(c.getId())
                    .reason(reason(reading)).urgent(urgent(plan, c)).build());
            c.setState(SmmComment.State.HANDED_OVER);
        } else {
            c.setState(plan.publicReply() == null && plan.privateReply() == null && !plan.hideComment()
                    ? SmmComment.State.SKIPPED : SmmComment.State.PLANNED);
        }
        comments.save(c);
        if (urgent(plan, c)) alerts.maybeAlert(c.getMerchantId());
        if (newHotLead) leadAlerts.maybeAlert(c.getMerchantId());
    }

    /** What the Writer needs: the comment, the seller's voice and memory, and the latest replies so the angle varies. */
    private ReplyWriter.Context writerContext(SmmComment c, Reading reading, SmmSellerProfile p, SmmAccount account) {
        List<String> faq = faqs.findByMerchantId(c.getMerchantId()).stream()
                .sorted(Comparator.comparing(SmmFaq::getId).reversed()).limit(40)
                .map(f -> f.getQuestion().replace('\n', ' ') + " → " + f.getAnswer().replace('\n', ' ')).toList();
        List<String> recent = new ArrayList<>();
        for (SmmReply.Kind kind : List.of(SmmReply.Kind.PUBLIC, SmmReply.Kind.PRIVATE)) {
            replies.findTop3ByMerchantIdAndKindOrderByIdDesc(c.getMerchantId(), kind).forEach(r -> {
                if (r.getText() != null) recent.add(r.getText().length() > 160 ? r.getText().substring(0, 160) : r.getText());
            });
        }
        CommentReplyPlanner.Shop shop = shop(p, account);
        StringBuilder facts = new StringBuilder();
        if (shop.location() != null) facts.append("Location: ").append(shop.location()).append(". ");
        if (shop.landmark() != null) facts.append("Directions: ").append(shop.landmark()).append(". ");
        if (shop.hours() != null) facts.append("Open: ").append(shop.hours()).append(". ");
        if (shop.whatsappLink() != null) facts.append("WhatsApp: ").append(shop.whatsappLink()).append(". ");
        ReplyWriter.Voice voice = new ReplyWriter.Voice(shop.name(), p.getTone(), p.getVoiceSamples(), p.getNoGoPhrases(),
                faq, facts.isEmpty() ? null : facts.toString().trim());
        String name = c.getCommenterName() == null ? null : c.getCommenterName().split("\\s+")[0];
        return new ReplyWriter.Context(c.getMerchantId(), c.getText(), name, reading.language(),
                String.valueOf(reading.intent()), voice, recent);
    }

    /**
     * An old comment the seller already answered themselves: the agents don't answer it again or hand it over. They
     * still read it (the buyer shows in Leads) and still hide it if it is bad or shows a phone number.
     */
    static Plan alreadyAnswered(Plan plan) {
        return new Plan(null, null, plan.hideComment(), false, plan.demand(), false);
    }

    /**
     * Whether this comment's replies go out: the seller is out of shadow mode and has not paused the agents, and the
     * comment came in live. Old comments fetched at connect are not answered in the seller's name weeks later.
     */
    static boolean sendable(SmmSellerProfile profile, SmmComment c) {
        return !profile.isShadowMode() && !profile.isAgentsPaused() && !Boolean.TRUE.equals(c.getBackfilled());
    }

    /** Old comments fetched after connecting are hidden if bad, but are not an emergency: no urgent alert. */
    static boolean urgent(Plan plan, SmmComment c) {
        return plan.urgent() && !Boolean.TRUE.equals(c.getBackfilled());
    }

    /** Free answers without the model: emoji-only praise. */
    static Optional<Reading> ruleReading(String text) {
        if (!text.isBlank() && text.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return Optional.of(new Reading(Intent.COMPLIMENT, false, false, null, null, null, "en", "high"));
        }
        return Optional.empty();
    }

    private void logReply(SmmComment c, SmmReply.Kind kind, String text, SmmReply.Status status, ReplyWriter.Result written) {
        if (text == null) return;
        replies.save(SmmReply.builder().merchantId(c.getMerchantId()).commentId(c.getId()).kind(kind).text(text)
                // who a private reply goes to, so the buyer's answer can be matched to it (the sender updates it
                // with the recipient ID Meta returns)
                .recipientId(kind == SmmReply.Kind.PRIVATE ? c.getCommenterId() : null)
                .model(written.model()).angle(written.angle()).checkerVerdict(written.verdict())
                .status(status).build());
    }

    private void hide(SmmComment c, SmmAccount account) {
        SmmReply.SmmReplyBuilder hide = SmmReply.builder().merchantId(c.getMerchantId()).commentId(c.getId()).kind(SmmReply.Kind.HIDE);
        if (account == null || account.getStatus() != SmmAccount.Status.ACTIVE) {
            replies.save(hide.status(SmmReply.Status.FAILED).error("Account not connected").build());
            log.warn("SokoReply: comment {} NOT hidden (merchant {}): account not connected", c.getId(), c.getMerchantId());
            return;
        }
        try {
            graph.hideComment(c.getPlatform(), c.getCommentId(), account.getAccessToken());
            replies.save(hide.status(SmmReply.Status.SENT).sentAt(LocalDateTime.now(SmmIntakeService.NAIROBI)).build());
            log.info("SokoReply: hid comment {} on {} (merchant {})", c.getId(), c.getPlatform(), c.getMerchantId());
        } catch (Exception e) {
            if (alreadyHidden(e.getMessage())) { // hidden by hand in Business Suite before we got to it
                replies.save(hide.status(SmmReply.Status.SENT).sentAt(LocalDateTime.now(SmmIntakeService.NAIROBI)).build());
                log.info("SokoReply: comment {} was already hidden (merchant {})", c.getId(), c.getMerchantId());
                return;
            }
            replies.save(hide.status(SmmReply.Status.FAILED).error(e.getMessage()).build());
            log.warn("SokoReply: comment {} NOT hidden (merchant {}): {}", c.getId(), c.getMerchantId(), MetaGraphClient.redact(e.getMessage()));
        }
    }

    private Lead upsertLead(SmmComment c, Reading r, boolean phone) {
        Lead.Platform platform = c.getPlatform() == SocialPlatform.INSTAGRAM ? Lead.Platform.INSTAGRAM : Lead.Platform.FACEBOOK;
        String sender = c.getCommenterId() == null ? "comment:" + c.getCommentId() : c.getCommenterId();
        Lead lead = leads.findFirstByMerchantIdAndSenderPsidAndPlatform(c.getMerchantId(), sender, platform)
                .orElseGet(() -> Lead.builder().merchantId(c.getMerchantId()).senderPsid(sender).platform(platform)
                        .source(Lead.LeadSource.COMMENT).firstMessage(c.getText()).messageCount(0).build());
        lead.setSenderName(c.getCommenterName());
        lead.setLatestMessage(c.getText());
        lead.setMessageCount((lead.getMessageCount() == null ? 0 : lead.getMessageCount()) + 1);
        lead.setPostId(c.getPostId());
        if (r.productCode() != null) lead.setInterestedItemCode(r.productCode());
        if (phone) lead.setPhoneNumber(firstPhone(c.getText()));
        Lead.Temperature t = temperature(r, phone);
        boolean wasHot = lead.getTemperature() == Lead.Temperature.HOT;
        if (lead.getTemperature() == null || t.ordinal() > lead.getTemperature().ordinal()) lead.setTemperature(t);
        // A buyer who just became hot on a live comment: the seller gets a WhatsApp to call them
        if (!wasHot && lead.getTemperature() == Lead.Temperature.HOT && !Boolean.TRUE.equals(c.getBackfilled())) {
            lead.setHotAlertPending(true);
            lead.setHotWant(want(r));
        }
        lead.setLastContactAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
        return leads.save(lead);
    }

    /** What a hot buyer wants, for the seller's alert: "black, size 12", or the intent. */
    static String want(Reading r) {
        List<String> parts = new ArrayList<>();
        if (r.colour() != null && !r.colour().isBlank()) parts.add(r.colour().trim());
        if (r.size() != null && !r.size().isBlank()) parts.add("size " + r.size().trim());
        if (r.place() != null && !r.place().isBlank()) parts.add("in " + r.place().trim());
        if (!parts.isEmpty()) return String.join(", ", parts);
        return switch (r.intent() == null ? Intent.OTHER : r.intent()) {
            case BOOKING -> "to book";
            case SET -> "the full set";
            default -> "to buy";
        };
    }

    /** HOT: wants it now or left a number; WARM: asked price, size, place...; COLD otherwise. */
    static Lead.Temperature temperature(Reading r, boolean phone) {
        if (phone || r.intent() == Intent.READY_TO_BUY || r.intent() == Intent.BOOKING || r.intent() == Intent.SET) return Lead.Temperature.HOT;
        if (CommentReplyPlanner.isBuyer(r)) return Lead.Temperature.WARM;
        return Lead.Temperature.COLD;
    }

    static SmmHandover.Reason reason(Reading r) {
        if (r.negative()) return SmmHandover.Reason.NEGATIVE;
        return switch (r.intent() == null ? Intent.OTHER : r.intent()) {
            case COMPLAINT -> SmmHandover.Reason.COMPLAINT;
            case AFTER_SALES -> SmmHandover.Reason.AFTER_SALES;
            case QUALITY_DOUBT -> SmmHandover.Reason.QUALITY_DOUBT;
            case BUSINESS_LEAD -> SmmHandover.Reason.BUSINESS_LEAD;
            case ADVICE -> SmmHandover.Reason.ADVICE;
            case SET -> SmmHandover.Reason.SET;
            default -> SmmHandover.Reason.UNSURE;
        };
    }

    /** Catalogue rows for the products tagged on this post; colours and sizes come in as rows under one product. */
    private List<Item> itemsOnPost(SmmComment c) {
        if (c.getPostId() == null) return List.of();
        Set<String> codes = postItems.findByMerchantIdAndPostId(c.getMerchantId(), c.getPostId()).stream()
                .map(SmmPostItem::getItemCode).collect(Collectors.toSet());
        if (codes.isEmpty()) return List.of();
        List<Item> items = new ArrayList<>();
        for (Inventory i : inventory.findByMerchantId(String.valueOf(c.getMerchantId()))) {
            String product = i.getParentCode() != null ? i.getParentCode() : i.getItemCode();
            if (!codes.contains(product) && !codes.contains(i.getItemCode())) continue;
            items.add(new Item(product, i.getItemName(), i.getColour(), i.getSize(), i.getUnitPrice(),
                    i.isService() ? null : i.getAvailableStock()));
        }
        return items;
    }

    static List<CommentReader.PostProduct> products(List<Item> items) {
        Map<String, List<Item>> byProduct = items.stream().collect(Collectors.groupingBy(Item::productCode, LinkedHashMap::new, Collectors.toList()));
        return byProduct.entrySet().stream().map(e -> new CommentReader.PostProduct(e.getKey(), e.getValue().get(0).name(),
                join(e.getValue().stream().map(Item::colour)), join(e.getValue().stream().map(Item::size)))).toList();
    }

    private static String join(java.util.stream.Stream<String> values) {
        String s = values.filter(Objects::nonNull).distinct().collect(Collectors.joining(", "));
        return s.isEmpty() ? null : s;
    }

    private static CommentReplyPlanner.Shop shop(SmmSellerProfile p, SmmAccount account) {
        String name = account == null || account.getPageName() == null ? "our shop" : account.getPageName().replaceFirst("^@", "");
        String wa = p.getWhatsappNumber() == null || p.getWhatsappNumber().isBlank() ? null
                : "https://wa.me/" + p.getWhatsappNumber().replaceAll("\\D", "").replaceFirst("^0", "254");
        return new CommentReplyPlanner.Shop(name, p.getLocationText(), null, wa, p.getLandmarkDirections(), p.getOpeningHours());
    }

    private static String firstPhone(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:\\+?254|0)[17]\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{3}").matcher(text);
        return m.find() ? m.group().replaceAll("[\\s-]", "") : null;
    }

    /** Meta refuses to hide a comment that is already hidden (subcode 1446036, "Duplicate request to mark as spam"). */
    static boolean alreadyHidden(String error) {
        return error != null && (error.contains("1446036") || error.contains("already marked as spam"));
    }
}
