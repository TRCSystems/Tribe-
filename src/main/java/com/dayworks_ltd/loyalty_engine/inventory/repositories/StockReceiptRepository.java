package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.StockReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StockReceiptRepository extends JpaRepository<StockReceipt, Long> {

    List<StockReceipt> findByApproverPhoneAndStatusOrderByCreatedAtAsc(String approverPhone, StockReceipt.Status status);

    List<StockReceipt> findByMerchantIdAndReceiptNumberAndStatusIn(String merchantId, String receiptNumber,
                                                                   Collection<StockReceipt.Status> statuses);
}
