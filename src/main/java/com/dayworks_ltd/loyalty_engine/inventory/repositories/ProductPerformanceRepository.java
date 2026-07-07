package com.dayworks_ltd.loyalty_engine.inventory.repositories;


import com.dayworks_ltd.loyalty_engine.inventory.models.ProductPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductPerformanceRepository extends JpaRepository<ProductPerformance, Long> {

    // Find today's record for a specific item
    Optional<ProductPerformance> findByMerchantIdAndItemCodeAndPeriodDate(
            String merchantId, String itemCode, LocalDate periodDate);

    // All performance records for a merchant in a date range
    List<ProductPerformance> findByMerchantIdAndPeriodDateBetween(
            String merchantId, LocalDate from, LocalDate to);

    // Top performing items by net profit for a period
    @Query("SELECT p FROM ProductPerformance p WHERE p.merchantId = :merchantId " +
            "AND p.periodDate BETWEEN :from AND :to " +
            "ORDER BY p.netProfit DESC")
    List<ProductPerformance> findTopPerformersByMerchantAndPeriod(
            @Param("merchantId") String merchantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    // All items performance for a specific date
    List<ProductPerformance> findByMerchantIdAndPeriodDate(
            String merchantId, LocalDate periodDate);

    // Total net profit for a merchant in a period
    @Query("SELECT SUM(p.netProfit) FROM ProductPerformance p " +
            "WHERE p.merchantId = :merchantId " +
            "AND p.periodDate BETWEEN :from AND :to")
    BigDecimal sumNetProfitByMerchantAndPeriod(
            @Param("merchantId") String merchantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}