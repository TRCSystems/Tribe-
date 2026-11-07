package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SmmCommentRepository extends JpaRepository<SmmComment, Long> {
    /** A webhook delivered twice must be stored once. */
    boolean existsByPlatformAndCommentId(SocialPlatform platform, String commentId);

    Optional<SmmComment> findByPlatformAndCommentId(SocialPlatform platform, String commentId);

    /** Work queue for the background worker. */
    List<SmmComment> findTop50ByStateOrderByReceivedAtAsc(SmmComment.State state);

    /** Portal Comments page, newest first. */
    Page<SmmComment> findByMerchantIdOrderByReceivedAtDesc(Long merchantId, Pageable page);

    Page<SmmComment> findByMerchantIdAndPostIdOrderByReceivedAtDesc(Long merchantId, String postId, Pageable page);

    /** Bad comments on one post since a time: grouped into one urgent alert. */
    long countByMerchantIdAndPostIdAndNegativeTrueAndReceivedAtAfter(Long merchantId, String postId, LocalDateTime since);
}
