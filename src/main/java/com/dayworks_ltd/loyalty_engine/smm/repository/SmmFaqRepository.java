package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmFaq;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmFaqRepository extends JpaRepository<SmmFaq, Long> {
    List<SmmFaq> findByMerchantId(Long merchantId);
}
