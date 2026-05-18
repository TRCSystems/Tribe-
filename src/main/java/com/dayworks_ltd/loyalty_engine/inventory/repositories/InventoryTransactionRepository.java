package com.dayworks_ltd.loyalty_engine.inventory.repositories;
import com.dayworks_ltd.loyalty_engine.auth.enums.TransactionType;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryTransaction;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
@Repository
public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByMerchantIdOrderByTransactionDateDesc(String merchantId);

    List<InventoryTransaction> findByMerchantIdAndTransactionTypeOrderByTransactionDateDesc(
            String merchantId, TransactionType transactionType);

    List<InventoryTransaction> findByMerchantIdAndTransactionDateBetweenOrderByTransactionDateDesc(
            String merchantId, LocalDateTime start, LocalDateTime end);

    List<InventoryTransaction> findByMerchantIdAndItemCodeOrderByTransactionDateDesc(
            String merchantId, String itemCode);

    @Query("SELECT t FROM InventoryTransaction t WHERE t.merchantId = :merchantId " +
            "ORDER BY t.transactionDate DESC LIMIT 50")
    List<InventoryTransaction> findRecentTransactions(@Param("merchantId") String merchantId);
}