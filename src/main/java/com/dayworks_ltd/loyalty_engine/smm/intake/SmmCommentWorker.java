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
import com.dayworks_ltd.loyalty_engine.smm.order.SmmOrderLinkService;
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
    private final SmmOrderLinkService orderLinks;
    private final SmmCaptionIndexService captionIndex;

    /** A viral post is handled at most this many comments a minute; other posts go ahead meanwhile. */
    @Value("${smm.worker.per-post-per-minute:30}")
    private int perPostPerMinute = 30;
    /**
     * Links in the first private message. Off by default: a first message from a Page with a link in it tends to land
     * in the buyer's spam folder. The links go out once the buyer answers (see {@link SmmConversationAgent}).
     */
    @Value("${smm.first-dm-links:false}")
    private boolean firstDmLinks = false;
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
        Plan plan = CommentReplyPlanner.plan(text, reading, items, shop(profile, account, c.getPostId(), items, reading), age);
        // Answered already (by the seller, or the same person said the same thing a moment ago): no second reply,
        // but a phone number or a bad comment is still hidden
        if (Boolean.TRUE.equals(c.getSellerAnswered()) || repeat) plan = alreadyAnswered(plan);
        if (!firstDmLinks) plan = withoutDmLinks(plan);

        // Replies: queued for the sender when the seller is live, otherwise only logged. The Writer puts the
        // planner's facts in the seller's voice (also in shadow mode, so the seller sees what would go out); old
        // comments fetched at connect and paused sellers keep the planner's wording, nothing is sent for them anyway.
        SmmReply.Status status = sendable(profile, c) ? SmmReply.Status.PENDING : SmmReply.Status.LOGGED;
        ReplyWriter.Result written = Boolean.TRUE.equals(c.getBackfilled()) || profile.isAgentsPaused()
                ? new ReplyWriter.Result(plan.publicReply(), plan.privateReply(), null, "planner", null)
                : writer.write(plan, writerContext(c, reading, profile, account));
        logReply(c, SmmReply.Kind.PUBLIC, plain(withDmHint(written.publicReply(), written.privateReply(), reading.language())), status, written);
        // The Writer knows the shop's WhatsApp link from the shop facts and may put it back: take it out again
        logReply(c, SmmReply.Kind.PRIVATE, plain(firstDmLinks ? written.privateReply() : withoutLinkLines(written.privateReply())), status, written);

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

    private static final java.util.regex.Pattern DM_MENTION = java.util.regex.Pattern.compile("(?i)\\b(dm|inbox)\\b");

    /** The first private message without its link lines; the closing question stays. */
    static Plan withoutDmLinks(Plan plan) {
        String kept = withoutLinkLines(plan.privateReply());
        if (Objects.equals(kept, plan.privateReply())) return plan;
        return new Plan(plan.publicReply(), kept, plan.hideComment(), plan.handover(), plan.demand(), plan.urgent());
    }

    /** A message without its link lines. One that was only a link is kept whole: better sent than dropped. */
    static String withoutLinkLines(String dm) {
        if (dm == null || !CommentReplyPlanner.hasLink(dm)) return dm;
        String kept = Arrays.stream(dm.split("\n")).filter(line -> !CommentReplyPlanner.hasLink(line))
                .collect(Collectors.joining("\n")).replaceAll("\n{3,}", "\n\n").strip();
        return kept.isEmpty() ? dm : kept;
    }

    /** A first message from a Page often lands under Message requests or Spam: the public reply says where to look. */
    static String withDmHint(String publicReply, String privateReply, String language) {
        if (publicReply == null || privateReply == null || !DM_MENTION.matcher(publicReply).find()) return publicReply;
        return publicReply.strip() + ("sw".equalsIgnoreCase(language)
                ? " (Angalia Message requests.)"
                : " (Check Message requests.)");
    }

    private static final java.util.regex.Pattern EMOJI = java.util.regex.Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{20E3}]");

    /** A reply without emojis: plain text reads like a person who is busy selling, not like an advert. */
    static String plain(String text) {
        if (text == null) return null;
        // An emoji standing between two sentences was doing a full stop's job
        String joined = text.replaceAll("(?<=[\\p{L}\\p{N}])[ \\t]*" + EMOJI.pattern() + "+[ \\t]*(?=\\p{Lu})", ". ");
        return EMOJI.matcher(joined).replaceAll("").replaceAll("[ \\t]{2,}", " ").replaceAll(" +([.,!?])", "$1")
                .replaceAll("(?m)[ \\t]+$", "").strip();
    }

    /** What the chat agent may say for the post a comment was on: the shop, its items and the seller's voice. */
    record ChatFacts(CommentReplyPlanner.Shop shop, List<Item> items, ReplyWriter.Voice voice) {}

    ChatFacts chatFacts(SmmComment c, SmmSellerProfile p, SmmAccount account) {
        List<Item> items = itemsOnPost(c);
        CommentReplyPlanner.Shop shop = shop(p, account, c.getPostId(), items, null);
        return new ChatFacts(shop, items, voice(c.getMerchantId(), p, shop, captionFor(c, account), c.getPostId()));
    }

    /** Captions by post, kept an hour: {caption or "", expiry in millis}. */
    private final Map<String, Object[]> captions = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What the seller wrote on the post the comment is under. Most sellers put the price, sizes and how to order
     * there and never tag a product, so this is often the only place the answer is.
     */
    String captionFor(SmmComment c, SmmAccount account) {
        if (c.getPostId() == null || account == null || account.getAccessToken() == null) return null;
        long now = System.currentTimeMillis();
        Object[] hit = captions.get(c.getPostId());
        if (hit == null || (long) hit[1] < now) {
            String caption = null;
            try {
                caption = graph.postCaption(c.getPlatform(), c.getPostId(), account.getAccessToken());
            } catch (Exception e) {
                log.warn("SokoReply: couldn't read the caption of post {}: {}", c.getPostId(), MetaGraphClient.redact(String.valueOf(e.getMessage())));
            }
            hit = new Object[]{caption == null ? "" : caption, now + 3_600_000L};
            if (captions.size() > 2000) captions.clear();
            captions.put(c.getPostId(), hit);
            if (caption != null) captionIndex.indexOnce(c.getMerchantId(), c.getPostId(), caption);
        }
        String caption = (String) hit[0];
        return caption.isEmpty() ? null : caption;
    }

    private ReplyWriter.Voice voice(Long merchantId, SmmSellerProfile p, CommentReplyPlanner.Shop shop, String caption, String postId) {
        List<String> faq = faqs.findByMerchantId(merchantId).stream()
                .sorted(Comparator.comparing(SmmFaq::getId).reversed()).limit(40)
                .map(f -> f.getQuestion().replace('\n', ' ') + " → " + f.getAnswer().replace('\n', ' ')).toList();
        StringBuilder facts = new StringBuilder();
        if (shop.location() != null) facts.append("Location: ").append(shop.location()).append(". ");
        if (shop.landmark() != null) facts.append("Directions: ").append(shop.landmark()).append(". ");
        if (shop.hours() != null) facts.append("Open: ").append(shop.hours()).append(". ");
        if (shop.whatsappLink() != null) facts.append("WhatsApp: ").append(shop.whatsappLink()).append(". ");
        if (caption != null) {
            String oneLine = caption.replaceAll("\\s+", " ").strip();
            facts.append("\nThe caption the seller wrote on this post (its prices, sizes and details are facts about the item in the post): \"")
                    .append(oneLine.length() > 900 ? oneLine.substring(0, 900) : oneLine).append("\"");
        }
        String range = captionIndex.rangeFactsFor(merchantId, postId);
        if (range != null) facts.append('\n').append(range);
        return new ReplyWriter.Voice(shop.name(), p.getTone(), p.getVoiceSamples(), p.getNoGoPhrases(),
                faq, facts.isEmpty() ? null : facts.toString().trim());
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
        CommentReplyPlanner.Shop shop = shop(p, account, c.getPostId(), itemsOnPost(c), reading);
        ReplyWriter.Voice voice = voice(c.getMerchantId(), p, shop, captionFor(c, account), c.getPostId());
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

    /**
     * orderLink opens the SokoReply order page for this Reel (same link for every commenter on it, created once);
     * whatsappLink carries the matched item pre-typed, the way the plan describes ("Hi, nataka suitcase 20kg white
     * @1,500"), so the seller sees exactly what the buyer wants without typing it themselves.
     */
    private CommentReplyPlanner.Shop shop(SmmSellerProfile p, SmmAccount account, String postId, List<Item> items, Reading reading) {
        String name = account == null || account.getPageName() == null ? "our shop" : account.getPageName().replaceFirst("^@", "");
        String waBase = p.getWhatsappNumber() == null || p.getWhatsappNumber().isBlank() ? null
                : "https://wa.me/" + p.getWhatsappNumber().replaceAll("\\D", "").replaceFirst("^0", "254");
        String wa = waBase == null ? null : waBase + preTypedQuery(matchedItem(items, reading));
        String orderLink = items.isEmpty() || postId == null ? null : orderLinkFor(p.getMerchantId(), postId);
        return new CommentReplyPlanner.Shop(name, p.getLocationText(), orderLink, wa, p.getLandmarkDirections(), p.getOpeningHours());
    }

    private String orderLinkFor(Long merchantId, String postId) {
        try {
            return orderLinks.getOrCreate(merchantId, postId).url();
        } catch (Exception e) {
            log.warn("SokoReply: couldn't build an order link for post {}: {}", postId, e.getMessage());
            return null;
        }
    }

    /** The item the comment is about, when it's clear: what the Reader matched, or the post's only item. */
    static Item matchedItem(List<Item> items, Reading reading) {
        if (items.isEmpty()) return null;
        if (reading != null && reading.productCode() != null) {
            for (Item i : items) if (i.productCode().equals(reading.productCode())) return i;
        }
        return items.size() == 1 ? items.get(0) : null;
    }

    static String preTypedQuery(Item item) {
        if (item == null) return "";
        StringBuilder sb = new StringBuilder("Hi, nataka ").append(item.name());
        if (item.colour() != null) sb.append(' ').append(item.colour());
        if (item.size() != null) sb.append(" size ").append(item.size());
        if (item.price() != null) sb.append(" @ KES ").append(item.price().stripTrailingZeros().toPlainString());
        return "?text=" + java.net.URLEncoder.encode(sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
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
