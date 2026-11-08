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

    Page<Lead> findByMerchantIdAndTemperatureOrderByLastContactAtDesc(Long merchantId, Lead.Temperature temperature, Pageable page);

    Page<Lead> findByMerchantIdAndPostIdOrderByLastContactAtDesc(Long merchantId, String postId, Pageable page);

    Page<Lead> findByMerchantIdAndPostIdAndTemperatureOrderByLastContactAtDesc(Long merchantId, String postId, Lead.Temperature temperature, Pageable page);
}
