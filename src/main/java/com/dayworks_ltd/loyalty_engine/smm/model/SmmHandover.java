package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A comment a person must handle. Urgent ones (complaints, bad comments) are sent to the seller at once as a
 * short WhatsApp notification; the details are read in the portal.
 */
@Entity
@Table(name = "smm_handover",
        indexes = {
                @Index(name = "idx_smm_handover_merchant", columnList = "merchant_id, created_at"),
                @Index(name = "idx_smm_handover_open", columnList = "merchant_id, answered_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmHandover {

    public enum Reason { COMPLAINT, NEGATIVE, AFTER_SALES, HAGGLE, BULK, UNSURE, QUALITY_DOUBT, BUSINESS_LEAD, ADVICE, SET }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** smm_comment.id */
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    @Column(nullable = false)
    @Builder.Default
    private boolean urgent = false;

    /** WhatsApp number the alert went to. */
    @Column(name = "sent_to", length = 20)
    private String sentTo;

    @Column(name = "alerted_at")
    private LocalDateTime alertedAt;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    /** What the seller did, in their words or as picked in the portal. */
    @Column(columnDefinition = "TEXT")
    private String outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
