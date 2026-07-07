package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.credit_engine.dto.KraInvoiceDetails;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.SupplierInvoice;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.SupplierInvoiceRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.KraEtimsService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KraVerificationJobService {

    private final SupplierInvoiceRepository invoiceRepository;
    private final KraEtimsService kraEtimsService;

    @Scheduled(fixedDelay = 120_000)
    @Transactional
    public void processVerificationBatch() {
        List<SupplierInvoice> batch = invoiceRepository.claimPendingBatch(20);

        if (batch.isEmpty()) {
            log.debug("KRA-VERIFY - No pending invoices found");
            return;
        }

        log.info("KRA-VERIFY - Processing batch of {} invoices", batch.size());

        for (SupplierInvoice invoice : batch) {
            invoice.setKraVerificationStatus("PROCESSING");
            invoiceRepository.save(invoice);   // Lock it immediately

            verifyOne(invoice);
        }
    }
    private void verifyOne(SupplierInvoice invoice) {
        log.info("KRA-VERIFY - Starting verification - invoiceId={}, cuin={}, status={}, attempts={}",
                invoice.getId(),
                invoice.getKraControlUnitInvoiceNo(),
                invoice.getKraVerificationStatus(),
                invoice.getKraVerificationAttempts());

        try {
            if (invoice.getKraControlUnitInvoiceNo() == null) {
                invoice.setKraVerificationStatus("NO_CUIN");
                invoice.setKraVerificationAttempts(0);
                invoice.setKraCheckedAt(LocalDateTime.now());
                log.warn("KRA-VERIFY - NO_CUIN - invoiceId={}", invoice.getId());
                return;
            }

            Optional<KraInvoiceDetails> result = kraEtimsService.checkInvoice(
                    invoice.getKraControlUnitInvoiceNo(),
                    invoice.getInvoiceDate()
            );

            invoice.setKraCheckedAt(LocalDateTime.now());
            invoice.setKraVerificationAttempts(invoice.getKraVerificationAttempts() + 1);

            if (result.isPresent()) {
                KraInvoiceDetails kra = result.get();
                invoice.setKraVerificationStatus("VERIFIED");
                invoice.setKraApproved(true);
                invoice.setKraVerifiedAt(LocalDateTime.now());

                // Populate fields
                invoice.setKraSupplierPin(kra.getSupplierPIN());
                invoice.setKraTotalAmount(kra.getTotalInvoiceAmount());
                invoice.setKraDeviceSerial(kra.getDeviceSerialNumber());
                invoice.setKraControlUnitInvoiceNo(kra.getControlUnitInvoiceNumber());
                invoice.setKraTraderInvoiceNo(kra.getTraderSystemInvoiceNumber());
                invoice.setKraSupplierName(kra.getSupplierName());

                if (kra.getSalesDate() != null) {
                    invoice.setKraInvoiceDate(LocalDate.parse(kra.getSalesDate()));
                }

                log.info("KRA-VERIFY - SUCCESS - invoiceId={} cuin={} supplier={} amount={}",
                        invoice.getId(),
                        invoice.getKraControlUnitInvoiceNo(),
                        kra.getSupplierName(),
                        kra.getTotalInvoiceAmount());

            } else {
                invoice.setKraVerificationStatus("INVALID");
                invoice.setKraApproved(false);
                log.warn("KRA-VERIFY - INVALID - invoiceId={} cuin={}",
                        invoice.getId(), invoice.getKraControlUnitInvoiceNo());
            }

        } catch (Exception e) {
            log.error("KRA-VERIFY - EXCEPTION - invoiceId={} cuin={}",
                    invoice.getId(), invoice.getKraControlUnitInvoiceNo(), e);

            int attempts = invoice.getKraVerificationAttempts() + 1;
            invoice.setKraVerificationAttempts(attempts);
            invoice.setKraCheckedAt(LocalDateTime.now());

            if (attempts >= 10) {
                invoice.setKraVerificationStatus("UNVERIFIABLE");
                invoice.setKraNextRetryAt(null);
                log.error("KRA-VERIFY - UNVERIFIABLE after {} attempts - invoiceId={}", attempts, invoice.getId());
            } else {
                invoice.setKraVerificationStatus("KRA_DOWN");
                invoice.setKraNextRetryAt(calculateNextRetry(attempts));
                log.warn("KRA-VERIFY - KRA_DOWN - attempt={} invoiceId={} nextRetry={}",
                        attempts, invoice.getId(), invoice.getKraNextRetryAt());
            }
        }

        invoiceRepository.save(invoice);
        log.debug("KRA-VERIFY - Saved invoiceId={} with final status={}",
                invoice.getId(), invoice.getKraVerificationStatus());
    }
    private LocalDateTime calculateNextRetry(int attempts) {
        long minutes = switch (attempts) {
            case 1 -> 5;
            case 2 -> 15;
            case 3 -> 60;
            case 4 -> 240;
            default -> 1440;
        };
        return LocalDateTime.now().plusMinutes(minutes);
    }
}