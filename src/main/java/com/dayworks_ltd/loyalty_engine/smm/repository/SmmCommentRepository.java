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

    /** Work queue, live comments first: a backfill of old comments never delays a buyer asking right now. */
    @org.springframework.data.jpa.repository.Query("SELECT c FROM SmmComment c WHERE c.state = :state "
            + "ORDER BY CASE WHEN c.backfilled = true THEN 1 ELSE 0 END, c.receivedAt ASC")
    List<SmmComment> queue(@org.springframework.data.repository.query.Param("state") SmmComment.State state, Pageable page);

    /** Portal Comments page, newest first. */
    Page<SmmComment> findByMerchantIdOrderByReceivedAtDesc(Long merchantId, Pageable page);

    Page<SmmComment> findByMerchantIdAndPostIdOrderByReceivedAtDesc(Long merchantId, String postId, Pageable page);

    /** Portal Comments page with filters; a null filter means "any". */
    @org.springframework.data.jpa.repository.Query("SELECT c FROM SmmComment c WHERE c.merchantId = :m "
            + "AND (:postId IS NULL OR c.postId = :postId) AND (:intent IS NULL OR c.intent = :intent) "
            + "AND (:buyer IS NULL OR c.buyer = :buyer) AND (:negative IS NULL OR c.negative = :negative) "
            + "ORDER BY c.receivedAt DESC")
    Page<SmmComment> search(@org.springframework.data.repository.query.Param("m") Long merchantId,
                            @org.springframework.data.repository.query.Param("postId") String postId,
                            @org.springframework.data.repository.query.Param("intent") String intent,
                            @org.springframework.data.repository.query.Param("buyer") Boolean buyer,
                            @org.springframework.data.repository.query.Param("negative") Boolean negative,
                            Pageable page);

    /** Posts that have comments, for the filter list: post ID and how many comments. */
    @org.springframework.data.jpa.repository.Query("SELECT c.postId, COUNT(c) FROM SmmComment c WHERE c.merchantId = :m AND c.postId IS NOT NULL "
            + "GROUP BY c.postId ORDER BY MAX(c.receivedAt) DESC")
    List<Object[]> postsWithComments(@org.springframework.data.repository.query.Param("m") Long merchantId);

    /** Bad comments on one post since a time: grouped into one urgent alert. */
    long countByMerchantIdAndPostIdAndNegativeTrueAndReceivedAtAfter(Long merchantId, String postId, LocalDateTime since);
}
