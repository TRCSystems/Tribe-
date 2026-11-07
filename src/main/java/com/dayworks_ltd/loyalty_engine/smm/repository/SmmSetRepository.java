package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmSetRepository extends JpaRepository<SmmSet, Long> {
    List<SmmSet> findByMerchantIdAndActiveTrue(Long merchantId);
}
