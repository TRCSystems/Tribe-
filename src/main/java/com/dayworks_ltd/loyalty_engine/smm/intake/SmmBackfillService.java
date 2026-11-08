package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.intake.SmmIntakeService.Incoming;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient.FetchedComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * After a seller connects, fetches the last 30 days of comments on their recent posts and Reels so they see results on
 * day one. The comments join the normal queue (after live ones) and get the old-comment rules: past 7 days no private
 * reply, a bad one is hidden without an urgent alert, and comments the seller already answered are not answered again.
 * Runs once per account, a minute or so after connecting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmBackfillService {

    static final Duration WINDOW = Duration.ofDays(30);
    static final int MAX_POSTS = 25;
    static final int MAX_PER_POST = 300;
    static final int MAX_PER_ACCOUNT = 2000;

    private final SmmAccountRepository accounts;
    private final SmmIntakeService intake;
    private final MetaGraphClient graph;

    @Value("${smm.backfill.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${smm.backfill-delay-ms:60000}", initialDelay = 45000)
    public void run() {
        if (!enabled) return;
        for (SmmAccount a : accounts.findByStatusAndBackfilledAtIsNull(SmmAccount.Status.ACTIVE)) {
            try {
                int stored = backfill(a, Instant.now());
                a.setBackfillError(null);
                log.info("SokoReply backfill: {} older comment(s) for {} (merchant {})", stored, a.getPageName(), a.getMerchantId());
            } catch (Exception e) {
                // Not retried in a loop: the seller can reconnect, or support clears backfilled_at to try again
                a.setBackfillError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().substring(0, Math.min(500, e.getMessage().length())));
                log.warn("SokoReply backfill failed for account {}: {}", a.getId(), e.getMessage());
            }
            a.setBackfilledAt(LocalDateTime.now(SmmIntakeService.NAIROBI));
            accounts.save(a);
        }
    }

    int backfill(SmmAccount a, Instant now) {
        Instant since = now.minus(WINDOW);
        List<String> posts = a.getPlatform() == SocialPlatform.INSTAGRAM
                ? graph.instagramMediaIdsSince(a.getPageId(), a.getAccessToken(), since, MAX_POSTS)
                : graph.facebookPostIdsSince(a.getPageId(), a.getAccessToken(), since.getEpochSecond(), MAX_POSTS);
        List<FetchedComment> fetched = new ArrayList<>();
        for (String post : posts) {
            if (fetched.size() >= MAX_PER_ACCOUNT) break;
            fetched.addAll(a.getPlatform() == SocialPlatform.INSTAGRAM
                    ? graph.instagramComments(post, a.getAccessToken(), MAX_PER_POST)
                    : graph.facebookComments(post, a.getAccessToken(), MAX_PER_POST));
        }
        Prepared p = prepare(a, fetched, since);
        return intake.storeBackfill(p.comments(), p.answeredBySeller());
    }

    /** What to store, and which comments the seller had already replied to. */
    record Prepared(List<Incoming> comments, Set<String> answeredBySeller) {}

    /**
     * Turns fetched comments into queue entries: the seller's own replies are not stored but mark the comment they
     * answered; comments older than the window are dropped; at most MAX_PER_ACCOUNT are kept.
     */
    static Prepared prepare(SmmAccount a, List<FetchedComment> fetched, Instant since) {
        Set<String> answered = new HashSet<>();
        List<Incoming> out = new ArrayList<>();
        for (FetchedComment c : fetched) {
            if (a.getPageId().equals(c.fromId())) {
                if (c.parentId() != null) answered.add(c.parentId());
                continue;
            }
            Instant at = MetaGraphClient.instant(c.createdTime());
            if (at != null && at.isBefore(since)) continue;
            if (c.text() == null || c.text().isBlank() || out.size() >= MAX_PER_ACCOUNT) continue;
            String parent = c.parentId() != null && !c.parentId().equals(c.postId()) ? c.parentId() : null;
            out.add(new Incoming(a.getPlatform(), a.getPageId(), c.id(), c.postId(), parent, c.fromId(), c.fromName(), c.text(),
                    at == null ? null : LocalDateTime.ofInstant(at, SmmIntakeService.NAIROBI)));
        }
        return new Prepared(out, answered);
    }
}
