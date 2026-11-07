package com.dayworks_ltd.loyalty_engine.crm.repositories;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CrmMessageRepository extends JpaRepository<CrmMessage, Long> {

    /** Marketing messages actually sent to a shop since a moment — for the frequency cap. */
    @Query("SELECT COUNT(m) FROM CrmMessage m WHERE m.distributorId = :distributorId AND m.shopMerchantId = :shopId " +
            "AND m.marketing = true AND m.status = :status AND m.createdAt >= :since")
    long countMarketingSince(@Param("distributorId") Long distributorId,
                             @Param("shopId") Long shopId,
                             @Param("status") CrmEnums.MessageStatus status,
                             @Param("since") LocalDateTime since);

    List<CrmMessage> findByDistributorIdAndShopMerchantIdOrderByCreatedAtDesc(Long distributorId, Long shopId, Pageable page);

    java.util.Optional<CrmMessage> findFirstByProviderMessageId(String providerMessageId);

    List<CrmMessage> findByDistributorIdOrderByCreatedAtDesc(Long distributorId, Pageable page);
}
