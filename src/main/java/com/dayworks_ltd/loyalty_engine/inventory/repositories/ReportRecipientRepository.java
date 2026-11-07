package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.ReportRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportRecipientRepository extends JpaRepository<ReportRecipient, Long> {

    List<ReportRecipient> findByMerchantIdOrderByCreatedAtAsc(String merchantId);

    Optional<ReportRecipient> findByMerchantIdAndPhone(String merchantId, String phone);

    long countByMerchantId(String merchantId);
}
