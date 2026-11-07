package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SmmReplyRepository extends JpaRepository<SmmReply, Long> {
    List<SmmReply> findByCommentIdOrderByCreatedAtAsc(Long commentId);

    /** The morning summary: what the agents did since yesterday morning. */
    List<SmmReply> findByMerchantIdAndCreatedAtAfterOrderByCreatedAtAsc(Long merchantId, LocalDateTime since);
}
