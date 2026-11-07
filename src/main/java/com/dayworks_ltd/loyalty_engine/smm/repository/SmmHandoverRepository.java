package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmHandover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmHandoverRepository extends JpaRepository<SmmHandover, Long> {
    /** Still waiting for the seller. */
    List<SmmHandover> findByMerchantIdAndAnsweredAtIsNullOrderByCreatedAtAsc(Long merchantId);

    List<SmmHandover> findByCommentId(Long commentId);
}
