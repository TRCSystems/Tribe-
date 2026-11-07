package com.dayworks_ltd.loyalty_engine.crm.repositories;

import com.dayworks_ltd.loyalty_engine.crm.models.RepAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepAssignmentRepository extends JpaRepository<RepAssignment, Long> {

    Optional<RepAssignment> findByDistributorIdAndShopMerchantId(Long distributorId, Long shopMerchantId);

    List<RepAssignment> findByDistributorId(Long distributorId);

    List<RepAssignment> findByRepUserId(Long repUserId);
}
