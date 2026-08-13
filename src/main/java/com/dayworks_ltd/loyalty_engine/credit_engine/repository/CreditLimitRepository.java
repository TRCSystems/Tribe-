package com.dayworks_ltd.loyalty_engine.credit_engine.repository;

import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditLimit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CreditLimitRepository extends JpaRepository<CreditLimit, Long> {

    Optional<CreditLimit> findByMerchantId(Long merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CreditLimit c WHERE c.merchantId = :merchantId")
    Optional<CreditLimit> findByMerchantIdForUpdate(@Param("merchantId") Long merchantId);

    List<CreditLimit> findAll();
}