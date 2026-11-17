package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient.Button;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmPostDraft;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmPostDraftRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Turns a seller's WhatsApp upload into a Page post: send one or a few photos, then a short description (price,
 * size, anything buyers should know), and the agent writes a caption in the seller's voice and posts it. Lets the
 * seller take a post down or correct its caption afterwards. Only sellers with a connected, active Facebook Page
 * are eligible; everyone else falls through to the usual WhatsApp routing (the till-watermark flow included).
 */
@Slf4j
@Service
public class SmmPostUploadService {

    /** How long a photo stays waiting for its description before a later, unrelated text is no longer treated as one. */
    static final Duration COLLECTING_WINDOW = Duration.ofHours(2);
    static final int MAX_PHOTOS = 10;
    /** Said before sending photos, so a stock receipt or till-watermark video isn't mistaken for a post. */
    static final Set<String> START_WORDS = Set.of("POST", "CHAPISHA");
    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final String AWAITING = "POST";

    @Value("${smm.post-upload.enabled:true}")
    private boolean enabled = true;

    private final SmmSellerProfileRepository profiles;
    private final SmmAccountRepository accounts;
    private final SmmPostDraftRepository drafts;
    private final WhatsAppCloudClient whatsapp;
    private final MetaGraphClient graph;
    private final ShoppingListReader claude;
    private final SmmCaptionIndexService captionIndex;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smm-post-upload");
        t.setDaemon(true);
        return t;
    });

    public SmmPostUploadService(SmmSellerProfileRepository profiles, SmmAccountRepository accounts, SmmPostDraftRepository drafts,
                                 WhatsAppCloudClient whatsapp, MetaGraphClient graph, ShoppingListReader claude,
                                 SmmCaptionIndexService captionIndex) {
        this.profiles = profiles;
        this.accounts = accounts;
        this.drafts = drafts;
        this.whatsapp = whatsapp;
        this.graph = graph;
        this.claude = claude;
        this.captionIndex = captionIndex;
    }

    record Caption(
            @JsonPropertyDescription("The post's caption: a few short lines in the seller's voice, covering everything the seller's description gave (price, size, what it is). No hashtags unless the seller used them. No emoji beyond what a Kenyan seller's post normally has (one or two).")
            String caption) {}

    static final String SYSTEM = """
            You write Facebook posts for Kenyan sellers from what they typed on WhatsApp to describe a product they're posting a \
            photo of. Turn it into a short, natural caption a buyer would stop and read: lead with what it is, then the price and \
            any size or detail the seller gave. Never add a price, size, stock claim or detail the seller didn't give. Never \
            invent a call to action beyond what's natural (a buyer already sees "message us" is implied on a Page).""";

    @SuppressWarnings("unchecked")
    public boolean handle(String from, String type, String content, Map<String, Object> message) {
        if (!enabled) return false;
        String wa = WhatsAppCloudClient.toWaNumber(from);
        if (wa == null) return false;
        SmmSellerProfile seller = eligibleSeller(wa);
        if (seller == null) return false;
        SmmAccount account = accounts.findByMerchantId(seller.getMerchantId()).stream()
                .filter(a -> a.getStatus() == SmmAccount.Status.ACTIVE && a.getAccessToken() != null
                        && a.getPlatform() == com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform.FACEBOOK)
                .findFirst().orElse(null);
        if (account == null) return false;

        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        boolean collecting = "POST".equals(seller.getAwaiting()) && seller.getAwaitingSince() != null
                && seller.getAwaitingSince().isAfter(now.minus(COLLECTING_WINDOW));
        if ("image".equals(type) && message != null && message.get("image") instanceof Map<?, ?> im
                && ((Map<String, Object>) im).get("id") != null) {
            // A photo only starts or continues a post once the seller has explicitly said POST first - otherwise
            // it's left for the till-watermark and stock-receipt flows, which also take seller photos.
            if (!collecting) return false;
            return collectPhoto(from, seller, String.valueOf(((Map<String, Object>) im).get("id")), now);
        }
        if ("text".equals(type) && content != null && !content.isBlank()) {
            String upper = content.trim().toUpperCase(Locale.ROOT);
            if (upper.equals("TAKE DOWN") || upper.equals("CHUKUA CHINI")) return takeDown(from, seller, account);
            if (upper.startsWith("EDIT:")) return edit(from, seller, account, content.substring(5).trim());
            if (!collecting && START_WORDS.contains(upper)) return startCollecting(from, seller, now);
            if (collecting) {
                SmmPostDraft draft = drafts.findFirstByMerchantIdAndStatusOrderByIdDesc(seller.getMerchantId(), SmmPostDraft.Status.COLLECTING).orElse(null);
                if (draft != null && draft.getMediaIds() != null && !draft.getMediaIds().isBlank()) {
                    return describe(from, seller, account, draft, content.trim());
                }
            }
        }
        return false;
    }

    private SmmSellerProfile eligibleSeller(String wa) {
        return profiles.findAll().stream()
                .filter(p -> wa.equals(WhatsAppCloudClient.toWaNumber(p.getWhatsappNumber())))
                .findFirst().orElse(null);
    }

    private boolean startCollecting(String from, SmmSellerProfile seller, LocalDateTime now) {
        seller.setAwaiting(AWAITING);
        seller.setAwaitingSince(now);
        profiles.save(seller);
        whatsapp.sendText(from, "📸 Send the photo(s) you want posted, then describe it (price, size, anything buyers should know).");
        return true;
    }

    private boolean collectPhoto(String from, SmmSellerProfile seller, String mediaId, LocalDateTime now) {
        boolean fresh = !"POST".equals(seller.getAwaiting()) || seller.getAwaitingSince() == null
                || seller.getAwaitingSince().isBefore(now.minus(COLLECTING_WINDOW));
        SmmPostDraft draft = fresh ? null
                : drafts.findFirstByMerchantIdAndStatusOrderByIdDesc(seller.getMerchantId(), SmmPostDraft.Status.COLLECTING).orElse(null);
        List<String> ids = new ArrayList<>(draft == null || draft.getMediaIds() == null || draft.getMediaIds().isBlank()
                ? List.of() : List.of(draft.getMediaIds().split(",")));
        if (ids.size() >= MAX_PHOTOS) {
            whatsapp.sendText(from, "That's enough photos for one post (" + MAX_PHOTOS + "). Describe it now and I'll post it, "
                    + "or send ORDER... no wait, just send the description.");
            return true;
        }
        ids.add(mediaId);
        if (draft == null) {
            draft = SmmPostDraft.builder().merchantId(seller.getMerchantId()).status(SmmPostDraft.Status.COLLECTING).build();
        }
        draft.setMediaIds(String.join(",", ids));
        drafts.save(draft);
        seller.setAwaiting(AWAITING);
        seller.setAwaitingSince(now);
        profiles.save(seller);
        whatsapp.sendText(from, ids.size() == 1
                ? "Got the photo 📷 Send more photos if you have them, or describe it now (price, size, anything buyers should know) and I'll post it."
                : "Got it, " + ids.size() + " photos so far. Send more, or describe it now and I'll post it.");
        return true;
    }

    private boolean describe(String from, SmmSellerProfile seller, SmmAccount account, SmmPostDraft draft, String text) {
        seller.setAwaiting(null);
        seller.setAwaitingSince(null);
        profiles.save(seller);
        draft.setSellerText(text);
        drafts.save(draft);
        whatsapp.sendText(from, "Posting it now ⏳ I'll send you the link here in a moment.");
        List<String> mediaIds = List.of(draft.getMediaIds().split(","));
        worker.submit(() -> post(from, seller, account, draft, mediaIds, text));
        return true;
    }

    void post(String from, SmmSellerProfile seller, SmmAccount account, SmmPostDraft draft, List<String> mediaIds, String text) {
        List<Path> files = new ArrayList<>();
        try {
            Caption written = claude.readText(SYSTEM, text, Caption.class);
            String caption = written == null || written.caption() == null || written.caption().isBlank() ? text : written.caption().strip();
            String token = account.getAccessToken();
            MetaGraphClient.PostResult result;
            if (mediaIds.size() == 1) {
                byte[] bytes = download(mediaIds.get(0), files);
                if (bytes == null) throw new IllegalStateException("couldn't download the photo");
                result = graph.postSinglePhoto(account.getPageId(), bytes, "post.jpg", caption, token);
            } else {
                List<String> uploaded = new ArrayList<>();
                for (String mediaId : mediaIds) {
                    byte[] bytes = download(mediaId, files);
                    if (bytes == null) continue;
                    uploaded.add(graph.uploadUnpublishedPhoto(account.getPageId(), bytes, "post.jpg", token));
                }
                if (uploaded.isEmpty()) throw new IllegalStateException("couldn't download any of the photos");
                result = graph.postMultiPhoto(account.getPageId(), uploaded, caption, token);
            }
            draft.setStatus(SmmPostDraft.Status.POSTED);
            draft.setCaption(caption);
            draft.setPagePostId(result.postId());
            draft.setPermalink(result.permalinkUrl());
            draft.setPostedAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
            drafts.save(draft);
            try {
                captionIndex.indexOnce(seller.getMerchantId(), result.postId(), caption);
            } catch (Exception e) {
                log.warn("SokoReply: couldn't index the new post's caption: {}", e.getMessage());
            }
            String link = result.permalinkUrl() != null ? result.permalinkUrl() : "(posted, but the link isn't ready yet)";
            whatsapp.sendButtons(from, "Posted ✅\n" + link + "\n\nReply TAKE DOWN to remove it, or EDIT: followed by a new caption to fix it.",
                    List.of(new Button("SMM_POST_SEEN", "OK")));
            log.info("SokoReply: posted {} photo(s) for merchant {}: {}", mediaIds.size(), seller.getMerchantId(), result.postId());
        } catch (Exception e) {
            draft.setStatus(SmmPostDraft.Status.FAILED);
            draft.setError(e.getMessage());
            drafts.save(draft);
            whatsapp.sendText(from, "Sorry, that post didn't go through. Please try sending the photos again.");
            log.warn("SokoReply: post upload failed for merchant {}: {}", seller.getMerchantId(), e.getMessage());
        } finally {
            files.forEach(SmmPostUploadService::deleteQuietly);
        }
    }

    private byte[] download(String mediaId, List<Path> cleanup) {
        try {
            Path dir = Files.createTempDirectory("smm-post-");
            Path file = dir.resolve("in.img");
            cleanup.add(dir);
            if (whatsapp.downloadMediaToFile(mediaId, file, MAX_BYTES) == null) return null;
            return Files.readAllBytes(file);
        } catch (Exception e) {
            log.warn("SokoReply: couldn't download media {} for a post: {}", mediaId, e.getMessage());
            return null;
        }
    }

    private boolean takeDown(String from, SmmSellerProfile seller, SmmAccount account) {
        SmmPostDraft last = drafts.findFirstByMerchantIdAndStatusAndPagePostIdIsNotNullOrderByPostedAtDesc(seller.getMerchantId(), SmmPostDraft.Status.POSTED).orElse(null);
        if (last == null) {
            whatsapp.sendText(from, "I don't have a recent post from you to take down.");
            return true;
        }
        try {
            graph.deletePost(last.getPagePostId(), account.getAccessToken());
            whatsapp.sendText(from, "Taken down ✅");
        } catch (Exception e) {
            whatsapp.sendText(from, "Sorry, I couldn't take that down. Please remove it from your Page directly.");
            log.warn("SokoReply: could not delete post {} for merchant {}: {}", last.getPagePostId(), seller.getMerchantId(), e.getMessage());
        }
        return true;
    }

    private boolean edit(String from, SmmSellerProfile seller, SmmAccount account, String newCaption) {
        if (newCaption.isEmpty()) {
            whatsapp.sendText(from, "Send EDIT: followed by the new caption, for example: EDIT: now KES 1,500");
            return true;
        }
        SmmPostDraft last = drafts.findFirstByMerchantIdAndStatusAndPagePostIdIsNotNullOrderByPostedAtDesc(seller.getMerchantId(), SmmPostDraft.Status.POSTED).orElse(null);
        if (last == null) {
            whatsapp.sendText(from, "I don't have a recent post from you to edit.");
            return true;
        }
        try {
            graph.editPostMessage(last.getPagePostId(), newCaption, account.getAccessToken());
            last.setCaption(newCaption);
            drafts.save(last);
            whatsapp.sendText(from, "Updated ✅");
        } catch (Exception e) {
            whatsapp.sendText(from, "Sorry, I couldn't update that post. Please edit it from your Page directly.");
            log.warn("SokoReply: could not edit post {} for merchant {}: {}", last.getPagePostId(), seller.getMerchantId(), e.getMessage());
        }
        return true;
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
