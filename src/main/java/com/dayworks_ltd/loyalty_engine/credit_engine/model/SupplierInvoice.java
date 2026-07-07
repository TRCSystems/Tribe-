package com.dayworks_ltd.loyalty_engine.credit_engine.model;

import jakarta.persistence.*;
import lombok.*;
import org.apache.commons.codec.digest.DigestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uc_invoice_identity",
                        columnNames = {"supplierId", "invoiceNumber", "invoiceDate"}),
                @UniqueConstraint(name = "uc_invoice_hash", columnNames = {"invoiceHash"})
        },
        indexes = {
                @Index(name = "idx_merchant_invoice", columnList = "merchantId,invoiceDate"),
                @Index(name = "idx_supplier_invoice", columnList = "supplierId,invoiceDate"),
                @Index(name = "idx_invoice_hash", columnList = "invoiceHash"),
                @Index(name = "idx_submission_id", columnList = "submissionId")
        }
)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplierInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;  // FK to merchants

    @Column
    private Integer matchScore; // 0–100

    @Column(nullable = false)
    private Long supplierId;  // FK to suppliers

    @Column(nullable = false, length = 100)
    private String invoiceNumber;  // "SIP095520"

    @Column(nullable = false)
    private LocalDate invoiceDate;  // 2025-12-18

    @Column
    private Boolean kraApproved;

    @Column(name = "kra_verification_status", nullable = false, length = 20)
    private String kraVerificationStatus = "PENDING";
// Values: PENDING, VERIFIED, INVALID, KRA_DOWN, UNVERIFIABLE, NO_CUIN

    @Column(nullable = false)
    private int kraVerificationAttempts = 0;

    @Column
    private LocalDateTime kraNextRetryAt;

    @Column
    private LocalDateTime kraVerifiedAt;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;  // 15060.34

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal vat;  // 2409.65

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;  // 17469.99

    @Column(length = 500)
    private String pdfUrl;  // S3 or cloud storage link

    @Column(precision = 4, scale = 2)
    private BigDecimal ocrConfidence;  // 0.95

    @Column(nullable = false, length = 64, unique = true)
    private String invoiceHash;  // SHA256 hash for fraud detection

    @Column(length = 50)
    private String submissionId;  // "CTZETFPV" from your API

    @Column(length = 100)
    private String externalDocumentNo;  // "ORD-208237-B5S8C4"

    @Column(length = 100)
    private String salesperson;  // "Dennis Gatimu"

    @Column
    private LocalDate dueDate;

    @Column(length = 255)
    private String paymentTerms;

    // KRA eTIMS fields
    @Column(length = 100)
    private String etimsInvoiceNo;  // "0020108360000620836"

    @Column(length = 100)
    private String etimsSerialNo;  // "KRAMW002202111010836"

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
//    @Column(length = 100)
//    private String etimsInvoiceNo;

//    @Column(length = 100)
//    private String etimsSerialNo;

    @Column(length = 20)
    private String kraSupplierPin;

    @Column
    private String supplierName;

    @Column(precision = 12, scale = 2)
    private BigDecimal kraTotalAmount;

    @Column(length = 255)
    private String kraSupplierName;

    @Column
    private LocalDate kraInvoiceDate;

    @Column(length = 100)
    private String kraDeviceSerial;

    @Column(length = 100)
    private String kraControlUnitInvoiceNo;

    @Column(length = 100)
    private String kraTraderInvoiceNo;
    // ==============================
    // VALIDATION FLAGS (BEHAVIOR SIGNALS)
    // ==============================


    @Column
    private boolean duplicateSuspected;
    @Column
    private boolean supplierMismatch;
    @Column
    private boolean amountMismatch;
    @Column
    private boolean dateMismatch;
    @Column
    private LocalDateTime kraCheckedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
        generateHash();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Generate SHA256 hash to prevent duplicate invoice uploads
     * Hash = SHA256(invoiceNumber + supplierId + total + invoiceDate)
     */
    public void generateHash() {
        if (invoiceNumber != null && supplierId != null && total != null && invoiceDate != null) {
            String raw = invoiceNumber +
                    supplierId.toString() +
                    total.toPlainString() +
                    invoiceDate.toString();
            this.invoiceHash = DigestUtils.sha256Hex(raw);
        }
    }
}