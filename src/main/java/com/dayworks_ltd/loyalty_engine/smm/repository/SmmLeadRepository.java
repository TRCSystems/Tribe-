package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** smm_leads: one row per buyer per platform, updated as they comment again. */
public interface SmmLeadRepository extends JpaRepository<Lead, Long> {
    Optional<Lead> findFirstByMerchantIdAndSenderPsidAndPlatform(Long merchantId, String senderPsid, Lead.Platform platform);

    Page<Lead> findByMerchantIdOrderByLastContactAtDesc(Long merchantId, Pageable page);
}
