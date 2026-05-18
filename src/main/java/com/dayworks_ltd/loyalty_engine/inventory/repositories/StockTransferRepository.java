package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.auth.enums.TransferStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    // Find transfers by recipient (Merchant's Pending Receipts)
    List<StockTransfer> findByRecipientAndStatusOrderByIssueDateDesc(
            Merchant recipient, TransferStatus status);

    // Find all transfers initiated by a distributor
    List<StockTransfer> findByDistributorAndStatusOrderByIssueDateDesc(
            Merchant distributor, TransferStatus status);

    // Find by transfer code
    Optional<StockTransfer> findByTransferCode(String transferCode);

    // Find pending receipts for a merchant
    @Query("SELECT st FROM StockTransfer st WHERE st.recipient = :merchant " +
            "AND st.status = 'ISSUED' ORDER BY st.issueDate DESC")
    List<StockTransfer> findPendingReceipts(@Param("merchant") Merchant merchant);

    // Count pending receipts
    long countByRecipientAndStatus(Merchant recipient, TransferStatus status);
}