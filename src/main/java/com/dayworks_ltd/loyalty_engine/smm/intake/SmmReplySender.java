package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmReply;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCommentRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmReplyRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Sends the replies the worker queued (PENDING): the public reply under the comment, then the private reply (DM).
 * Each reply is tried once; a failure is recorded with Meta's reason and not retried, so a buyer is never sent the
 * same message twice. A seller who pauses the agents or goes back to shadow mode stops anything not yet sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmReplySender {

    /** Meta allows the private reply up to 7 days after the comment. */
    static final Duration PRIVATE_REPLY_WINDOW = Duration.ofDays(7);

    private final SmmReplyRepository replies;
    private final SmmCommentRepository comments;
    private final SmmAccountRepository accounts;
    private final SmmSellerProfileRepository profiles;
    private final MetaGraphClient graph;

    @Scheduled(fixedDelayString = "${smm.sender-delay-ms:5000}", initialDelay = 25000)
    public void run() {
        for (SmmReply r : replies.findTop50ByStatusOrderByIdAsc(SmmReply.Status.PENDING)) {
            try {
                send(r);
            } catch (Exception e) {
                fail(r, e.getMessage());
            }
            replies.save(r);
        }
    }

    void send(SmmReply r) {
        SmmComment c = comments.findById(r.getCommentId()).orElse(null);
        if (c == null) {
            fail(r, "Comment not found");
            return;
        }
        Optional<SmmSellerProfile> profile = profiles.findByMerchantId(r.getMerchantId());
        if (profile.isEmpty() || profile.get().isShadowMode() || profile.get().isAgentsPaused()) {
            // Paused or back in shadow mode after this was queued: keep it as a record, don't send it
            r.setStatus(SmmReply.Status.LOGGED);
            r.setError(profile.isPresent() && profile.get().isAgentsPaused() ? "Agents paused" : "Shadow mode");
            return;
        }
        SmmAccount account = accounts.findById(c.getAccountId()).orElse(null);
        if (account == null || account.getStatus() != SmmAccount.Status.ACTIVE || account.getAccessToken() == null) {
            fail(r, "Account not connected");
            return;
        }
        LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);
        switch (r.getKind()) {
            case PUBLIC -> r.setMetaMessageId(graph.replyToComment(c.getPlatform(), c.getCommentId(), r.getText(), account.getAccessToken()));
            case PRIVATE -> {
                if (tooLateForPrivateReply(c, now)) {
                    fail(r, "More than 7 days after the comment: Meta no longer allows a private reply");
                    return;
                }
                MetaGraphClient.SentMessage sent = graph.privateReply(c.getCommentId(), r.getText(), account.getAccessToken());
                r.setMetaMessageId(sent.messageId());
                // The ID the buyer's answers will arrive with, so the reply rate can match them
                if (sent.recipientId() != null) r.setRecipientId(sent.recipientId());
            }
            case HIDE -> {
                // Hiding is done by the worker straight away; nothing queued
                r.setStatus(SmmReply.Status.LOGGED);
                return;
            }
        }
        r.setStatus(SmmReply.Status.SENT);
        r.setSentAt(now);
        r.setError(null);
        log.info("SokoReply: sent {} reply {} for comment {} (merchant {})", r.getKind(), r.getId(), c.getId(), r.getMerchantId());
    }

    static boolean tooLateForPrivateReply(SmmComment c, LocalDateTime now) {
        return c.getCommentCreatedAt() != null && c.getCommentCreatedAt().plus(PRIVATE_REPLY_WINDOW).isBefore(now);
    }

    private static void fail(SmmReply r, String why) {
        r.setStatus(SmmReply.Status.FAILED);
        r.setError(MetaGraphClient.redact(why));
        log.warn("SokoReply: {} reply {} not sent: {}", r.getKind(), r.getId(), r.getError());
    }
}
