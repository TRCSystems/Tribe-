package com.dayworks_ltd.loyalty_engine.inventory.models;

import com.dayworks_ltd.loyalty_engine.inventory.enums.CloseDayStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One end-of-day stock count session per merchant per trading date.
 * Holds the roll-up of the blind count; the per-item detail lives in {@link StockCountLine}.
 */
@Entity
@Table(name = "close_day",
        uniqueConstraints = @UniqueConstraint(name = "uk_close_day_merchant_date",
                columnNames = {"merchantId", "tradingDate"}),
        indexes = @Index(name = "idx_close_day_status", columnList = "status"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloseDay {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private LocalDate tradingDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CloseDayStatus status = CloseDayStatus.OPEN;

    private LocalDateTime closeInitiatedAt;
    private String closeInitiatedBy;
    private LocalDateTime closedAt;
    private String closedBy;

    @Column(length = 1000)
    private String closeNote;

    private Integer totalSkusMoved;
    private Integer totalSkusQueued;
    private Integer totalSkusCounted;
    private Integer totalSkusNotCounted;
    private Integer totalVariances;
    private Integer openVariances;

    /** Sum of shortage value (at unit cost) — always zero or positive. */
    @Column(precision = 14, scale = 2)
    private BigDecimal shortageValue;
    /** Sum of overage value (at unit cost) — always zero or positive. */
    @Column(precision = 14, scale = 2)
    private BigDecimal overageValue;
    /** Net of overage minus shortage. Negative means stock is missing. */
    @Column(precision = 14, scale = 2)
    private BigDecimal totalVarianceValue;

    @Column(precision = 14, scale = 2)
    private BigDecimal expectedCollections;
    @Column(precision = 14, scale = 2)
    private BigDecimal actualCollections;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    public boolean isClosed() {
        return status == CloseDayStatus.CLOSED || status == CloseDayStatus.CLOSED_WITH_EXCEPTION;
    }
}
