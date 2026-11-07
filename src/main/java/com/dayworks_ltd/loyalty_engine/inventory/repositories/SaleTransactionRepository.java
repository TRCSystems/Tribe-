package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.SaleTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
@Repository
public interface SaleTransactionRepository extends JpaRepository<SaleTransaction, Long> {
    List<SaleTransaction> findByMerchantIdAndSaleDateOrderBySaleDateTimeDesc(String merchantId, LocalDate saleDate);

    /** Units sold per item on a date, fastest movers first, as rows of [itemCode, sum(quantity)]. */
    @Query("SELECT s.itemCode, SUM(s.quantity) FROM SaleTransaction s " +
            "WHERE s.merchantId = :merchantId AND s.saleDate = :saleDate " +
            "GROUP BY s.itemCode ORDER BY SUM(s.quantity) DESC")
    List<Object[]> sumQuantityByItem(@Param("merchantId") String merchantId,
                                     @Param("saleDate") LocalDate saleDate);

    /** Units sold per item between two moments, fastest movers first, as rows of [itemCode, sum(quantity)]. */
    @Query("SELECT s.itemCode, SUM(s.quantity) FROM SaleTransaction s " +
            "WHERE s.merchantId = :merchantId AND s.saleDateTime >= :from AND s.saleDateTime < :to " +
            "GROUP BY s.itemCode ORDER BY SUM(s.quantity) DESC")
    List<Object[]> sumQuantityByItemBetween(@Param("merchantId") String merchantId,
                                            @Param("from") java.time.LocalDateTime from,
                                            @Param("to") java.time.LocalDateTime to);

    @Query("SELECT DISTINCT s.merchantId FROM SaleTransaction s WHERE s.saleDate = :saleDate")
    List<String> findMerchantIdsWithSalesOn(@Param("saleDate") LocalDate saleDate);

    @Query("SELECT DISTINCT s.merchantId FROM SaleTransaction s WHERE s.saleDate BETWEEN :from AND :to")
    List<String> findMerchantIdsWithSalesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Sales and margin for a date range, as one row of
     * [salesCount, revenue, costedRevenue, margin, linesWithoutCost].
     * Margin = total price - unit cost x quantity, over lines that have a cost.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT transaction_ref),
                   COALESCE(SUM(total_price), 0),
                   COALESCE(SUM(CASE WHEN unit_cost > 0 THEN total_price END), 0),
                   COALESCE(SUM(CASE WHEN unit_cost > 0 THEN total_price - unit_cost * quantity END), 0),
                   COALESCE(SUM(CASE WHEN unit_cost IS NULL OR unit_cost <= 0 THEN 1 ELSE 0 END), 0)
            FROM sale_transactions
            WHERE merchant_id = :merchantId AND sale_date BETWEEN :from AND :to
            """, nativeQuery = true)
    List<Object[]> salesSummary(@Param("merchantId") String merchantId,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);
}