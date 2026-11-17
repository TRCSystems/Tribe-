package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One seller's SokoReply billing plan and payment state. One row per merchant. */
@Entity
@Table(name = "smm_subscription", uniqueConstraints = @UniqueConstraint(name = "uk_smm_subscription_merchant", columnNames = "merchant_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmSubscription {

    public enum Status { PENDING_PAYMENT, ACTIVE, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SmmPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING_PAYMENT;

    /** For Starter only: which single platform the seller picked (Facebook or Instagram). */
    @Enumerated(EnumType.STRING)
    @Column(name = "starter_platform", length = 20)
    private SocialPlatform starterPlatform;

    /** M-Pesa STK push CheckoutRequestID / paymentReference for the payment currently awaited. */
    @Column(name = "checkout_request_id", length = 100)
    private String checkoutRequestId;

    @Column(name = "last_payment_reference", length = 100)
    private String lastPaymentReference;

    @Column(name = "last_payment_at")
    private LocalDateTime lastPaymentAt;

    /** The paid period runs until this moment; null until the first successful payment. */
    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == Status.ACTIVE && currentPeriodEnd != null && currentPeriodEnd.isAfter(LocalDateTime.now());
    }
}
