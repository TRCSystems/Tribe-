package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SmmSubscriptionRepository extends JpaRepository<SmmSubscription, Long> {

    Optional<SmmSubscription> findByMerchantId(Long merchantId);

    List<SmmSubscription> findByStatusAndCurrentPeriodEndBefore(SmmSubscription.Status status, LocalDateTime cutoff);
}
