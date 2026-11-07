package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stock received from a supplier, sent as a receipt photo on WhatsApp. Held until the shop's approver says yes,
 * then each line is added to stock (and new products are created).
 */
@Entity
@Table(name = "stock_receipt",
        indexes = {
                @Index(name = "idx_stock_receipt_approver", columnList = "approverPhone,status"),
                @Index(name = "idx_stock_receipt_number", columnList = "merchantId,receiptNumber")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReceipt {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String merchantId;

    /** 07… number of the staff member who sent the receipt. */
    @Column(nullable = false, length = 15)
    private String submittedBy;

    @Column(nullable = false, length = 15)
    private String approverPhone;

    private String supplierName;

    private String receiptNumber;

    /** As printed, YYYY-MM-DD when it could be read. */
    @Column(length = 20)
    private String receiptDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    /** JSON list of {itemCode, itemName, units, unitCost, isNew}. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String linesJson;

    /** WhatsApp media file kept for checking (see MediaArchive). */
    private String photoPath;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Builder.Default
    @Column(nullable = false)
    private Boolean shownOnWhatsApp = false;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime decidedAt;

    private String decidedBy;
}
