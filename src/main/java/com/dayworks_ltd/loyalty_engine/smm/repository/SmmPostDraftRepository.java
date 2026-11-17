package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmPostDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmmPostDraftRepository extends JpaRepository<SmmPostDraft, Long> {

    Optional<SmmPostDraft> findFirstByMerchantIdAndStatusOrderByIdDesc(Long merchantId, SmmPostDraft.Status status);

    /** The seller's most recent post, to act on "take it down" or "edit: ...". */
    Optional<SmmPostDraft> findFirstByMerchantIdAndStatusAndPagePostIdIsNotNullOrderByPostedAtDesc(Long merchantId, SmmPostDraft.Status status);
}
