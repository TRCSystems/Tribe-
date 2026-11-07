package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns Meta's comment webhooks into smm_comment rows (state NEW) for the worker. Only quick work happens here:
 * Meta wants an answer within seconds. A comment delivered twice is stored once (unique platform + comment ID).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmIntakeService {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final SmmAccountRepository accounts;
    private final SmmCommentRepository comments;

    /** One comment pulled out of a webhook, before it is matched to a shop. */
    public record Incoming(SocialPlatform platform, String accountId, String commentId, String postId, String parentId,
                           String fromId, String fromName, String text, LocalDateTime createdAt) {}

    /** How many new comments were stored. */
    public int ingest(Map<String, Object> payload) {
        int stored = 0;
        for (Incoming in : parse(payload)) {
            if (save(in)) stored++;
        }
        return stored;
    }

    @SuppressWarnings("unchecked")
    static List<Incoming> parse(Map<String, Object> payload) {
        String object = String.valueOf(payload.get("object"));
        SocialPlatform platform = "instagram".equals(object) ? SocialPlatform.INSTAGRAM : "page".equals(object) ? SocialPlatform.FACEBOOK : null;
        if (platform == null) return List.of();
        java.util.ArrayList<Incoming> out = new java.util.ArrayList<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) payload.getOrDefault("entry", List.of())) {
            String accountId = str(entry.get("id"));
            LocalDateTime entryTime = epoch(entry.get("time"));
            for (Map<String, Object> change : (List<Map<String, Object>>) entry.getOrDefault("changes", List.of())) {
                Map<String, Object> v = (Map<String, Object>) change.getOrDefault("value", Map.of());
                Map<String, Object> from = (Map<String, Object>) v.getOrDefault("from", Map.of());
                if (platform == SocialPlatform.FACEBOOK) {
                    // Page feed: only new comments (not likes, edits, removals or posts)
                    if (!"feed".equals(change.get("field")) || !"comment".equals(v.get("item")) || !"add".equals(v.get("verb"))) continue;
                    String postId = str(v.get("post_id")), parent = str(v.get("parent_id"));
                    out.add(new Incoming(platform, accountId, str(v.get("comment_id")), postId,
                            parent != null && !parent.equals(postId) ? parent : null,
                            str(from.get("id")), str(from.get("name")), str(v.get("message")),
                            Optional.ofNullable(epoch(v.get("created_time"))).orElse(entryTime)));
                } else {
                    if (!"comments".equals(change.get("field"))) continue;
                    Map<String, Object> media = (Map<String, Object>) v.getOrDefault("media", Map.of());
                    out.add(new Incoming(platform, accountId, str(v.get("id")), str(media.get("id")), str(v.get("parent_id")),
                            str(from.get("id")), str(from.get("username")), str(v.get("text")), entryTime));
                }
            }
        }
        return out;
    }

    private boolean save(Incoming in) {
        if (in.commentId() == null || in.accountId() == null) return false;
        Optional<SmmAccount> account = accounts.findByPlatformAndPageId(in.platform(), in.accountId());
        if (account.isEmpty() || account.get().getStatus() == SmmAccount.Status.DISCONNECTED) return false;
        SmmAccount a = account.get();
        a.setLastWebhookAt(LocalDateTime.now(NAIROBI));
        accounts.save(a);
        // The seller's own replies are theirs, not comments to answer
        if (in.accountId().equals(in.fromId())) return false;
        if (comments.existsByPlatformAndCommentId(in.platform(), in.commentId())) return false;
        try {
            comments.save(SmmComment.builder()
                    .merchantId(a.getMerchantId()).accountId(a.getId()).platform(in.platform())
                    .commentId(in.commentId()).postId(in.postId()).parentCommentId(in.parentId())
                    .commenterId(in.fromId()).commenterName(in.fromName()).text(in.text())
                    .commentCreatedAt(in.createdAt()).receivedAt(LocalDateTime.now(NAIROBI))
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // the same comment arrived twice at the same moment
        }
    }

    private static LocalDateTime epoch(Object value) {
        if (value instanceof Number n) return LocalDateTime.ofInstant(Instant.ofEpochSecond(n.longValue()), NAIROBI);
        if (value instanceof String s && !s.isBlank()) {
            try {
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(s)), NAIROBI);
            } catch (NumberFormatException e) {
                try {
                    return LocalDateTime.ofInstant(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s.replace("+0000", "+00:00"), Instant::from), NAIROBI);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
