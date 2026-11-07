package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Item;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Works through new comments: reads each one (two-step Reader), creates or warms up the lead, plans the move and
 * records it. Protective actions run from day one, even in shadow mode: a comment with a phone number or a harmful
 * comment is hidden, and urgent ones alert the seller. Public and private replies are only logged until sending
 * is built (Phase 3) and the seller is switched to live.
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

    @Scheduled(fixedDelayString = "${smm.worker-delay-ms:5000}", initialDelay = 20000)
    public void run() {
        if (!reader.isConfigured()) return;
        for (SmmComment c : comments.findTop50ByStateOrderByReceivedAtAsc(SmmComment.State.NEW)) {
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

        CommentReader.Result result = ruleReading(text)
                .map(r -> new CommentReader.Result(r, false))
                .orElseGet(() -> reader.readComment(text, products(items)));
        Reading reading = result.reading();
        c.applyReading(reading, phone, result.secondLook());

        if (Boolean.TRUE.equals(c.getBuyer()) || CommentReplyPlanner.isBuyer(reading)) {
            c.setLeadId(upsertLead(c, reading, phone).getId());
        }

        SmmSellerProfile profile = profiles.findByMerchantId(c.getMerchantId())
                .orElseGet(() -> SmmSellerProfile.builder().merchantId(c.getMerchantId()).build());
        SmmAccount account = accounts.findById(c.getAccountId()).orElse(null);
        Duration age = c.getCommentCreatedAt() == null ? Duration.ZERO
                : Duration.between(c.getCommentCreatedAt(), LocalDateTime.now(SmmIntakeService.NAIROBI));
        Plan plan = CommentReplyPlanner.plan(text, reading, items, shop(profile, account), age);

        // Replies: only logged for now (shadow mode, or sending not built yet)
        logReply(c, SmmReply.Kind.PUBLIC, plan.publicReply());
        logReply(c, SmmReply.Kind.PRIVATE, plan.privateReply());

        // Protective action, from day one and even when the seller paused the agents: hiding says nothing in their name
        if (plan.hideComment()) hide(c, account);

        if (plan.handover()) {
            handovers.save(SmmHandover.builder().merchantId(c.getMerchantId()).commentId(c.getId())
                    .reason(reason(reading)).urgent(plan.urgent()).build());
            c.setState(SmmComment.State.HANDED_OVER);
        } else {
            c.setState(plan.publicReply() == null && plan.privateReply() == null && !plan.hideComment()
                    ? SmmComment.State.SKIPPED : SmmComment.State.PLANNED);
        }
        comments.save(c);
        if (plan.urgent()) alerts.maybeAlert(c.getMerchantId());
    }

    /** Free answers without the model: emoji-only praise. */
    static Optional<Reading> ruleReading(String text) {
        if (!text.isBlank() && text.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return Optional.of(new Reading(Intent.COMPLIMENT, false, false, null, null, null, "en", "high"));
        }
        return Optional.empty();
    }

    private void logReply(SmmComment c, SmmReply.Kind kind, String text) {
        if (text == null) return;
        replies.save(SmmReply.builder().merchantId(c.getMerchantId()).commentId(c.getId()).kind(kind).text(text)
                .model("planner").status(SmmReply.Status.LOGGED).build());
    }

    private void hide(SmmComment c, SmmAccount account) {
        SmmReply.SmmReplyBuilder hide = SmmReply.builder().merchantId(c.getMerchantId()).commentId(c.getId()).kind(SmmReply.Kind.HIDE);
        if (account == null || account.getStatus() != SmmAccount.Status.ACTIVE) {
            replies.save(hide.status(SmmReply.Status.FAILED).error("Account not connected").build());
            return;
        }
        try {
            graph.hideComment(c.getPlatform(), c.getCommentId(), account.getAccessToken());
            replies.save(hide.status(SmmReply.Status.SENT).sentAt(LocalDateTime.now(SmmIntakeService.NAIROBI)).build());
        } catch (Exception e) {
            replies.save(hide.status(SmmReply.Status.FAILED).error(e.getMessage()).build());
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
        if (lead.getTemperature() == null || t.ordinal() > lead.getTemperature().ordinal()) lead.setTemperature(t);
        lead.setLastContactAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
        return leads.save(lead);
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
        return new CommentReplyPlanner.Shop(name, p.getLocationText(), null, wa);
    }

    private static String firstPhone(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:\\+?254|0)[17]\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{3}").matcher(text);
        return m.find() ? m.group().replaceAll("[\\s-]", "") : null;
    }
}
