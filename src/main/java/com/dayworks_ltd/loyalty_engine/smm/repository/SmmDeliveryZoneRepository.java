package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmDeliveryZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmDeliveryZoneRepository extends JpaRepository<SmmDeliveryZone, Long> {
    List<SmmDeliveryZone> findByMerchantIdAndActiveTrue(Long merchantId);
}
