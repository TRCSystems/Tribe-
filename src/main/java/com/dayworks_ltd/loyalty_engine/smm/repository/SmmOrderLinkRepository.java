package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmOrderLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmmOrderLinkRepository extends JpaRepository<SmmOrderLink, Long> {

    Optional<SmmOrderLink> findByToken(String token);

    Optional<SmmOrderLink> findByMerchantIdAndPostId(Long merchantId, String postId);
}
