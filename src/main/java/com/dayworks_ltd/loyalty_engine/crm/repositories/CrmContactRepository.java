package com.dayworks_ltd.loyalty_engine.crm.repositories;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CrmContactRepository extends JpaRepository<CrmContact, Long> {

    Optional<CrmContact> findByDistributorIdAndShopMerchantId(Long distributorId, Long shopMerchantId);

    List<CrmContact> findByDistributorId(Long distributorId);

    /** Used by STOP handling: the same number may be a contact for several distributors. */
    List<CrmContact> findByPhoneOrWhatsappNumber(String phone, String whatsappNumber);
}
