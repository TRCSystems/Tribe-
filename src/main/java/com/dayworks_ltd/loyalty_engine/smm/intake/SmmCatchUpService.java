package com.dayworks_ltd.loyalty_engine.smm.intake;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Picks up comments Meta never sent us. Webhooks are sometimes late or lost (and none arrive while the app is
 * unpublished), so every 10 minutes the latest posts of each connected account are read and any comment from the last
 * day that we don't have yet joins the queue as a live one: a bad comment is hidden and the seller alerted, a question
 * is answered. Comments we already have are skipped, so nothing is answered twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmCatchUpService {

    /** Only comments this recent are caught up: older ones were covered by the backfill at connect. */
    static final Duration WINDOW = Duration.ofHours(24);
    /** Posts published in the last 30 days, newest first: buyers comment on recent posts. */
    static final Duration POSTS_SINCE = Duration.ofDays(30);
    static final int MAX_POSTS = 10;
    static final int MAX_PER_POST = 100;

    private final SmmAccountRepository accounts;
    private final SmmIntakeService intake;
    private final MetaGraphClient graph;

    @Value("${smm.catchup.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${smm.catchup-delay-ms:600000}", initialDelay = 120000)
    public void run() {
        if (!enabled) return;
        for (SmmAccount a : accounts.findByStatus(SmmAccount.Status.ACTIVE)) {
            if (a.getBackfilledAt() == null || a.getAccessToken() == null) continue; // the backfill goes first
            try {
                int stored = catchUp(a, Instant.now());
                if (stored > 0) log.info("SokoReply catch-up: {} comment(s) Meta didn't send for {} (merchant {})",
                        stored, a.getPageName(), a.getMerchantId());
            } catch (Exception e) {
                log.warn("SokoReply catch-up failed for account {}: {}", a.getId(), MetaGraphClient.redact(e.getMessage()));
            }
        }
    }

    int catchUp(SmmAccount a, Instant now) {
        List<String> posts = a.getPlatform() == SocialPlatform.INSTAGRAM
                ? graph.instagramMediaIdsSince(a.getPageId(), a.getAccessToken(), now.minus(POSTS_SINCE), MAX_POSTS)
                : graph.facebookPostIdsSince(a.getPageId(), a.getAccessToken(), now.minus(POSTS_SINCE).getEpochSecond(), MAX_POSTS);
        List<FetchedComment> fetched = new ArrayList<>();
        for (String post : posts) {
            fetched.addAll(a.getPlatform() == SocialPlatform.INSTAGRAM
                    ? graph.instagramComments(post, a.getAccessToken(), MAX_PER_POST)
                    : graph.facebookComments(post, a.getAccessToken(), MAX_PER_POST));
        }
        SmmBackfillService.Prepared p = SmmBackfillService.prepare(a, fetched, now.minus(WINDOW));
        return intake.storeCaughtUp(p.comments(), p.answeredBySeller());
    }
}
