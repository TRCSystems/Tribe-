package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmmSellerProfileRepository extends JpaRepository<SmmSellerProfile, Long> {
    Optional<SmmSellerProfile> findByMerchantId(Long merchantId);
}
