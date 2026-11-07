package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Extra phone numbers that receive stock count SMS for a shop, on top of the
 * registered business phone (which always receives everything).
 */
@Entity
@Table(name = "report_recipient",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_recipient_merchant_phone",
                columnNames = {"merchantId", "phone"}),
        indexes = @Index(name = "idx_report_recipient_merchant", columnList = "merchantId"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 80)
    private String name;

    /** Normalised to 07XXXXXXXX / 01XXXXXXXX, the format used for business phones. */
    @Column(nullable = false, length = 15)
    private String phone;

    /** End-of-day count summary. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean receiveSummary = true;

    /** Instant SMS when a large shortage is found during a count. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean receiveAlerts = true;

    private String createdBy;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
}
