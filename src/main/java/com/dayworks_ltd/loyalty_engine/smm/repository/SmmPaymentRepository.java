package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SmmPaymentRepository extends JpaRepository<SmmPayment, Long> {

    List<SmmPayment> findByMerchantIdOrderByPaidAtDesc(Long merchantId);

    /** For MRR/renewal reporting across every seller. */
    List<SmmPayment> findByPaidAtBetweenOrderByPaidAtAsc(LocalDateTime from, LocalDateTime to);
}
