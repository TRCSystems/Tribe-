package com.dayworks_ltd.loyalty_engine.credit_engine.service;



import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceDataDTO;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceExtractionResponse;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceHeaderDTO;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.SupplierDTO;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.Supplier;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.SupplierInvoice;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.SupplierInvoiceRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePersistenceService {

    private final SupplierRepository supplierRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;

    /**
     * Resolves or creates the supplier, then persists the invoice.
     *
     * @param extractionResponse  the parsed OCR result from InvoiceAutomationService
     * @param merchantId          the real merchant ID (already resolved from User in the controller)
     * @return                    the persisted SupplierInvoice
     */
    @Transactional
    public SupplierInvoice persistInvoice(InvoiceExtractionResponse extractionResponse, Long merchantId) {

        InvoiceDataDTO data = extractionResponse.getData();
        SupplierDTO supplierData = data.getSupplier();
        InvoiceHeaderDTO invoiceData = data.getInvoice();

        // ── 1. IDEMPOTENCY GUARD ──────────────────────────────────────────────
        // If this submission was already persisted (e.g. retry after timeout),
        // return the existing record instead of double-writing.
        String submissionId = extractionResponse.getInvoiceSubmissionId();
        if (supplierInvoiceRepository.existsBySubmissionId(submissionId)) {
            log.warn("PERSIST-INVOICE - Submission {} already persisted. Skipping duplicate write.", submissionId);
            // Return existing — caller can still show it to the user
            return supplierInvoiceRepository.findAll().stream()  // swap for a proper query if needed
                    .filter(inv -> submissionId.equals(inv.getSubmissionId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Inconsistent state: flag set but record missing for " + submissionId));
        }

        // ── 2. RESOLVE SUPPLIER (upsert) ─────────────────────────────────────
        // Strategy: match on KRA PIN first (authoritative). Fall back to
        // normalized name only when PIN is absent (e.g. informal suppliers).
        Supplier supplier = resolveSupplier(supplierData);

        // ── 3. BUILD INVOICE ENTITY ───────────────────────────────────────────
        SupplierInvoice invoice = SupplierInvoice.builder()
                .merchantId(merchantId)
                .supplierId(supplier.getId())
                .invoiceNumber(invoiceData.getInvoiceNumber())
                .invoiceDate(invoiceData.getDate())              // already LocalDate — no parse needed
                .subtotal(invoiceData.getSubtotal())             // already BigDecimal
                .vat(invoiceData.getVat())                       // already BigDecimal
                .total(invoiceData.getTotal())                   // already BigDecimal
                .ocrConfidence(data.getConfidence())             // already BigDecimal
                .submissionId(submissionId)
                .build();

        // generateHash() is also called in @PrePersist, but calling it
        // here lets us check for a duplicate *before* hitting the DB.
        invoice.generateHash();

        // ── 4. DUPLICATE INVOICE GUARD (hash check) ───────────────────────────
        if (supplierInvoiceRepository.findByInvoiceHash(invoice.getInvoiceHash()).isPresent()) {
            log.warn("PERSIST-INVOICE - Duplicate invoice detected for hash {}. Merchant {} tried to re-upload invoice {}.",
                    invoice.getInvoiceHash(), merchantId, invoiceData.getInvoiceNumber());
            throw new DuplicateInvoiceException(
                    "This invoice has already been uploaded.",
                    invoiceData.getInvoiceNumber()
            );
        }

        SupplierInvoice saved = supplierInvoiceRepository.save(invoice);
        log.info("PERSIST-INVOICE - Saved invoice id={} for merchant={} supplier={}",
                saved.getId(), merchantId, supplier.getId());

        return saved;
    }


    private Supplier resolveSupplier(SupplierDTO supplierData) {

        String pin = supplierData.getPin();
        String rawName = supplierData.getName();

        // Normalize name the same way @PrePersist does — keeps lookups consistent.
        String normalizedName = rawName == null ? null :
                rawName.toLowerCase()
                        .replaceAll("[^a-z0-9\\s]", "")
                        .replaceAll("\\s+", " ")
                        .trim();

        // Try PIN first
        if (pin != null && !pin.isBlank()) {
            return supplierRepository.findByPin(pin)
                    .map(existing -> {
                        // Supplier exists — optionally update stale phone number
                        boolean dirty = false;
                        if (supplierData.getPhone() != null && !supplierData.getPhone().equals(existing.getPhone())) {
                            existing.setPhone(supplierData.getPhone());
                            dirty = true;
                        }
                        if (dirty) {
                            log.info("RESOLVE-SUPPLIER - Updated phone for existing supplier pin={}", pin);
                            return supplierRepository.save(existing);
                        }
                        return existing;
                    })
                    .orElseGet(() -> createNewSupplier(rawName, normalizedName, pin, supplierData.getPhone()));
        }

        // Fallback: match on normalized name
        if (normalizedName != null) {
            return supplierRepository.findByNormalizedName(normalizedName)
                    .orElseGet(() -> createNewSupplier(rawName, normalizedName, null, supplierData.getPhone()));
        }

        // Should never happen — validation upstream should catch this
        throw new IllegalArgumentException("Supplier data has neither PIN nor name — cannot persist.");
    }

    private Supplier createNewSupplier(String name, String normalizedName, String pin, String phone) {
        Supplier supplier = Supplier.builder()
                .name(name)
                .normalizedName(normalizedName)
                .pin(pin)
                .phone(phone)
                .build();
        Supplier saved = supplierRepository.save(supplier);
        log.info("RESOLVE-SUPPLIER - Created new supplier id={} name='{}' pin={}", saved.getId(), name, pin);
        return saved;
    }

    // ── Domain exception ──────────────────────────────────────────────────────
    public static class DuplicateInvoiceException extends RuntimeException {
        private final String invoiceNumber;
        public DuplicateInvoiceException(String message, String invoiceNumber) {
            super(message);
            this.invoiceNumber = invoiceNumber;
        }
        public String getInvoiceNumber() { return invoiceNumber; }
    }
}