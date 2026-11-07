package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A stock count SMS (alert or summary) held back until the shop's approver says yes on WhatsApp.
 * Only shops listed in reports.sms-approval.merchants have their reports held.
 */
@Entity
@Table(name = "held_report_sms",
        indexes = @Index(name = "idx_held_report_sms_approver", columnList = "approverPhone,status"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeldReportSms {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String merchantId;

    /** ReportRecipientService.ReportType name: SUMMARY or ALERT. */
    @Column(nullable = false, length = 20)
    private String reportType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** 07XXXXXXXX: the number that approves on WhatsApp. */
    @Column(nullable = false, length = 15)
    private String approverPhone;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** Whether the approval request reached WhatsApp (false when the 24-hour window was closed). */
    @Builder.Default
    @Column(nullable = false)
    private Boolean shownOnWhatsApp = false;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime decidedAt;

    private String decidedBy;
}
