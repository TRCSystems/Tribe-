package com.dayworks_ltd.loyalty_engine.crm.repositories;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CrmShopStatsRepository extends JpaRepository<CrmShopStats, Long> {

    List<CrmShopStats> findByDistributorId(Long distributorId);

    Optional<CrmShopStats> findByDistributorIdAndShopMerchantId(Long distributorId, Long shopMerchantId);
}
