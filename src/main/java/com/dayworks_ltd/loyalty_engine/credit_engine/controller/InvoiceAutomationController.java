package com.dayworks_ltd.loyalty_engine.credit_engine.controller;

import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceExtractionResponse;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.KraInvoiceDetails;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.SupplierInvoice;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.InvoiceAutomationService;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.InvoicePersistenceService;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.InvoiceToInventorySyncService;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.KraEtimsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
@Slf4j
public class InvoiceAutomationController {

    private final InvoiceAutomationService automationService;
    private final InvoiceToInventorySyncService syncService;
    private final InvoicePersistenceService persistenceService; // ← injected here
    private final UserRepository userRepository;
    private final KraEtimsService kraEtimsService;   // ← Injected here

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadInvoice(
            @RequestParam("invoice_file") MultipartFile invoiceFile,
            @RequestParam("merchant_id") String merchantId) {

        Map<String, Object> response = new HashMap<>();

        try {
            // ── 1. RESOLVE USER → MERCHANT ────────────────────────────────────
            Long userId = Long.parseLong(merchantId);

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "No user with specified ID exists"
                ));
            }

            User user = userOpt.get();
            String realMerchantId = user.getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            log.info("UPLOAD-INVOICE - userId={} resolved to merchantId={}", userId, realMerchantId);

            // ── 2. SUBMIT TO OCR + POLL FOR RESULT ───────────────────────────
            InvoiceExtractionResponse submission = automationService.submitInvoiceForProcessing(invoiceFile);
            String submissionId = submission.getInvoiceSubmissionId();

            InvoiceExtractionResponse finalResult = pollUntilComplete(submissionId, 12, 5000);

            // ── 3. PERSIST SUPPLIER + INVOICE ────────────────────────────────
            // realMerchantId comes back as String from user.getMerchantId().
            // SupplierInvoice.merchantId is Long — parse once, here, explicitly.
            Long resolvedMerchantId = Long.parseLong(realMerchantId);
            SupplierInvoice savedInvoice = persistenceService.persistInvoice(finalResult, resolvedMerchantId);

            // ── 4. BUILD RESPONSE ─────────────────────────────────────────────
            response.put("success", true);
            response.put("invoiceSubmissionId", submissionId);
            response.put("savedInvoiceId", savedInvoice.getId());
            response.put("supplierId", savedInvoice.getSupplierId());
            response.put("status", "complete");
            response.put("data", finalResult.getData());
            response.put("confidence", finalResult.getData().getConfidence());
            response.put("realMerchantId", realMerchantId);
            response.put("message", "Invoice extracted and saved. Review items to add to inventory.");

            return ResponseEntity.ok(response);

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "Invalid merchant_id format — must be a numeric user ID"
            ));

        } catch (InvoicePersistenceService.DuplicateInvoiceException e) {
            // Same invoice uploaded twice — not a server error, tell the client clearly
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "DUPLICATE",
                    "statusCode", 409,
                    "message", e.getMessage(),
                    "invoiceNumber", e.getInvoiceNumber()
            ));

        } catch (TimeoutException e) {
            response.put("success", false);
            response.put("status", "pending");
            response.put("invoiceSubmissionId", e.getSubmissionId());
            response.put("message", "Processing is taking longer than expected. Check status later using the submission ID.");
            return ResponseEntity.accepted().body(response); // 202

        } catch (Exception e) {
            log.error("UPLOAD-INVOICE - Unhandled failure", e);
            response.put("success", false);
            response.put("message", "Processing failed: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }


    @PostMapping("/kra/check")
    public ResponseEntity<Map<String, Object>> checkKraInvoice(@RequestBody Map<String, String> request) {
        try {
            String invoiceNumber = request.get("invoiceNumber");
            String invoiceDateStr = request.get("invoiceDate");

            if (invoiceNumber == null || invoiceDateStr == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "invoiceNumber and invoiceDate are required"
                ));
            }

            LocalDate invoiceDate = LocalDate.parse(invoiceDateStr);

            Optional<KraInvoiceDetails> kraResult = kraEtimsService.checkInvoice(invoiceNumber, invoiceDate);

            if (kraResult.isPresent()) {
                return ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "message", "Invoice found in KRA system",
                        "invoiceNumber", invoiceNumber,
                        "invoiceDate", invoiceDate,
                        "kraData", kraResult.get()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "status", "NOT_FOUND",
                        "message", "Invoice not found in KRA or service unavailable",
                        "invoiceNumber", invoiceNumber,
                        "invoiceDate", invoiceDate
                ));
            }

        } catch (Exception e) {
            log.error("KRA check failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to check KRA: " + e.getMessage()
            ));
        }
    }

    // ── Polling helper ────────────────────────────────────────────────────────
    private InvoiceExtractionResponse pollUntilComplete(
            String submissionId, int maxAttempts, long delayMs) throws TimeoutException {

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            InvoiceExtractionResponse result = automationService.getInvoiceExtractionResult(submissionId);

            if ("complete".equalsIgnoreCase(result.getStatus())) {
                return result;
            }
            if ("error".equalsIgnoreCase(result.getStatus())) {
                throw new RuntimeException("Extraction failed: " + result.getError());
            }

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            log.info("POLL - attempt {}/{} submissionId={} status={}", attempt, maxAttempts, submissionId, result.getStatus());
        }

        throw new TimeoutException("Timeout waiting for OCR completion", submissionId);
    }

    private static class TimeoutException extends Exception {
        private final String submissionId;
        public TimeoutException(String msg, String submissionId) {
            super(msg);
            this.submissionId = submissionId;
        }
        public String getSubmissionId() { return submissionId; }
    }
}
