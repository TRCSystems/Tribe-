package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmHandover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SmmHandoverRepository extends JpaRepository<SmmHandover, Long> {
    /** Still waiting for the seller. */
    List<SmmHandover> findByMerchantIdAndAnsweredAtIsNullOrderByCreatedAtAsc(Long merchantId);

    List<SmmHandover> findByCommentId(Long commentId);

    /** Urgent ones the seller has not been alerted about yet. */
    List<SmmHandover> findByMerchantIdAndUrgentTrueAndAlertedAtIsNull(Long merchantId);

    /** Merchants with urgent handovers still to alert (for the sweep). */
    List<SmmHandover> findByUrgentTrueAndAlertedAtIsNull();

    /** At most one urgent alert an hour per seller, so a viral reel cannot flood them. */
    boolean existsByMerchantIdAndUrgentTrueAndAlertedAtAfter(Long merchantId, LocalDateTime since);

    /** Questions waiting for the seller (not the urgent bad comments), oldest first. */
    List<SmmHandover> findByMerchantIdAndUrgentFalseAndAnsweredAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
            Long merchantId, java.time.LocalDateTime since);
}
