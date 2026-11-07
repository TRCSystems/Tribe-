package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WholesalePriceConfigRepository extends JpaRepository<WholesalePriceConfig, Long> {

    @Query("""
        SELECT w FROM WholesalePriceConfig w
        WHERE w.itemCode = :itemCode
          AND w.merchantId = :merchantId
          AND w.isActive = true
          AND w.effectiveFrom <= :today
          AND (w.effectiveTo IS NULL OR w.effectiveTo >= :today)
        ORDER BY w.effectiveFrom DESC, w.id DESC
        LIMIT 1
    """)
    Optional<WholesalePriceConfig> findActivePrice(
            @Param("itemCode") String itemCode,
            @Param("merchantId") String merchantId,
            @Param("today") LocalDate today);

    /** The current wholesale price; a removed price (isActive = false) no longer counts. Newest wins on the same day. */
    @Query("""
    SELECT w FROM WholesalePriceConfig w
    WHERE w.itemCode = :itemCode
      AND w.merchantId = :merchantId
      AND w.isActive = true
    ORDER BY w.effectiveFrom DESC, w.id DESC
    LIMIT 1
""")
    Optional<WholesalePriceConfig> findLatestPrice(
            @Param("itemCode") String itemCode,
            @Param("merchantId") String merchantId);

    /** All active wholesale prices of a merchant (newest first), for listing prices in one query. */
    @Query("""
    SELECT w FROM WholesalePriceConfig w
    WHERE w.merchantId = :merchantId
      AND w.isActive = true
    ORDER BY w.effectiveFrom DESC, w.id DESC
""")
    List<WholesalePriceConfig> findActiveByMerchant(@Param("merchantId") String merchantId);

    List<WholesalePriceConfig> findByItemCodeAndMerchantIdAndIsActiveTrue(String itemCode, String merchantId);
}