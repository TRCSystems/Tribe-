package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SmmReplyRepository extends JpaRepository<SmmReply, Long> {
    List<SmmReply> findByCommentIdOrderByCreatedAtAsc(Long commentId);

    /** The sender's queue, oldest first, so a comment's public reply goes before its private one. */
    List<SmmReply> findTop50ByStatusOrderByIdAsc(SmmReply.Status status);

    /** The morning summary: what the agents did since yesterday morning. */
    List<SmmReply> findByMerchantIdAndCreatedAtAfterOrderByCreatedAtAsc(Long merchantId, LocalDateTime since);

    /** A buyer's message: the latest private reply that went to them, to mark it answered. */
    Optional<SmmReply> findFirstByMerchantIdAndKindAndStatusAndRecipientIdAndSentAtAfterOrderBySentAtDesc(
            Long merchantId, SmmReply.Kind kind, SmmReply.Status status, String recipientId, LocalDateTime since);

    /** Reply rate: private replies sent in a period. */
    List<SmmReply> findByMerchantIdAndKindAndStatusAndSentAtAfter(Long merchantId, SmmReply.Kind kind, SmmReply.Status status, LocalDateTime since);
}
