package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface WholesalePriceConfigRepository extends JpaRepository<WholesalePriceConfig, Long> {

    @Query("""
        SELECT w FROM WholesalePriceConfig w
        WHERE w.itemCode = :itemCode
          AND w.merchantId = :merchantId
          AND w.isActive = true
          AND w.effectiveFrom <= :today
          AND (w.effectiveTo IS NULL OR w.effectiveTo >= :today)
        ORDER BY w.effectiveFrom DESC
        LIMIT 1
    """)
    Optional<WholesalePriceConfig> findActivePrice(
            @Param("itemCode") String itemCode,
            @Param("merchantId") String merchantId,
            @Param("today") LocalDate today);
}