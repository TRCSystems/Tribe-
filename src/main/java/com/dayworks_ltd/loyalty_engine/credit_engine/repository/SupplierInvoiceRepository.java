package com.dayworks_ltd.loyalty_engine.credit_engine.repository;

import com.dayworks_ltd.loyalty_engine.credit_engine.model.SupplierInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, Long> {

    /**
     * Duplicate detection via SHA256 hash.
     * Same invoice number + supplier + total + date = same document.
     */
    Optional<SupplierInvoice> findByInvoiceHash(String invoiceHash);

    /**
     * Check if a submission ID was already processed (idempotency guard).
     */
    boolean existsBySubmissionId(String submissionId);

    @Modifying
    @Query(value = """
    SELECT * FROM supplier_invoices\s
    WHERE kra_verification_status IN ('PENDING', 'KRA_DOWN')
      AND kra_control_unit_invoice_no IS NOT NULL
      AND (kra_next_retry_at IS NULL OR kra_next_retry_at <= NOW())
    ORDER BY created_at ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
   \s""", nativeQuery = true)
    List<SupplierInvoice> claimPendingBatch(@Param("batchSize") int batchSize);
}