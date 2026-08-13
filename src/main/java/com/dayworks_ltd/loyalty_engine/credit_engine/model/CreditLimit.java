package com.dayworks_ltd.loyalty_engine.credit_engine.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_limit", uniqueConstraints = @UniqueConstraint(columnNames = "merchant_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "approved_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedLimit;

    @Column(name = "outstanding_balance", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal outstandingBalance = BigDecimal.ZERO;

    @Column(name = "trade_score")
    private Integer tradeScore;

    @Column(name = "trade_grade", length = 10)
    private String tradeGrade;

    @Column(name = "last_recalculated_at")
    private LocalDateTime lastRecalculatedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Computed in application code only — no DB-generated column backs this. */
    @Transient
    public BigDecimal getAvailableLimit() {
        return approvedLimit.subtract(outstandingBalance).max(BigDecimal.ZERO);
    }

    @PreUpdate
    @PrePersist
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}