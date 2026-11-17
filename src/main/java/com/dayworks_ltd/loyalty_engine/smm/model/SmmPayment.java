package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One confirmed SokoReply subscription payment, kept forever (never updated or overwritten) so MRR, renewals
 * and churn can be reported on. {@link SmmSubscription} only holds the merchant's current plan/period; this is
 * the ledger of what was actually paid, when.
 */
@Entity
@Table(name = "smm_payment", indexes = @Index(name = "idx_smm_payment_merchant", columnList = "merchant_id, paid_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SmmPlan plan;

    @Column(name = "amount_kes", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountKes;

    @Column(name = "mpesa_receipt", length = 100)
    private String mpesaReceipt;

    /** The paid period this payment covers, i.e. the subscription's new currentPeriodEnd at the time. */
    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;
}
