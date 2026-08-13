package com.dayworks_ltd.loyalty_engine.credit_engine.repository;



import com.dayworks_ltd.loyalty_engine.credit_engine.model.ConsignmentInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsignmentInvoiceRepository extends JpaRepository<ConsignmentInvoice, Long> {

    Optional<ConsignmentInvoice> findByExternalReference(String externalReference);

    List<ConsignmentInvoice> findByAccountReferenceAndBillManagerStatusOrderBySentAtAsc(
            String accountReference, String billManagerStatus);
}
