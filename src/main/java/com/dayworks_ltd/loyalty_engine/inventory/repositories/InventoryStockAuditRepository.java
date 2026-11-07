package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryStockAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface InventoryStockAuditRepository extends JpaRepository<InventoryStockAudit, Long> {

    @Query("SELECT DISTINCT a.itemCode FROM InventoryStockAudit a WHERE a.merchantId = :merchantId " +
            "AND a.actionType IN :actionTypes AND a.createdAt >= :from AND a.createdAt < :to")
    List<String> findItemCodesWithActionBetween(@Param("merchantId") String merchantId,
                                                @Param("actionTypes") Collection<String> actionTypes,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    @Query("SELECT DISTINCT a.merchantId FROM InventoryStockAudit a " +
            "WHERE a.actionType IN :actionTypes AND a.createdAt >= :from AND a.createdAt < :to")
    List<String> findMerchantIdsWithActionBetween(@Param("actionTypes") Collection<String> actionTypes,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    /** Stock movements of a shop in a period, oldest first (audit trail report). */
    List<InventoryStockAudit> findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(
            String merchantId, LocalDateTime from, LocalDateTime to);

    /** Stock movements of one item in a period, oldest first. */
    List<InventoryStockAudit> findByMerchantIdAndItemCodeAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(
            String merchantId, String itemCode, LocalDateTime from, LocalDateTime to);
}
