package com.dayworks_ltd.loyalty_engine.crm.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Per-shop buying profile for a distributor, recomputed nightly (and on demand) from orders.
 * Drives segments, the call list and the automations.
 */
@Entity
@Table(name = "crm_shop_stats",
        uniqueConstraints = @UniqueConstraint(name = "uk_crm_stats_dist_shop",
                columnNames = {"distributorId", "shopMerchantId"}),
        indexes = @Index(name = "idx_crm_stats_segment", columnList = "distributorId, segment"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrmShopStats {

    public enum Segment {
        /** Linked to the distributor but has never ordered. */
        PROSPECT,
        /** First order in the last 30 days. */
        NEW,
        /** Orders often and spends well. */
        CHAMPION,
        /** Near its usual reorder day. */
        DUE,
        /** Ordering normally. */
        ACTIVE,
        /** Overdue by 1.5x its usual gap. */
        AT_RISK,
        /** Overdue by 3x its usual gap (and at least 21 days). */
        LOST
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long distributorId;

    @Column(nullable = false)
    private Long shopMerchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Segment segment;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Segment previousSegment;

    private LocalDateTime segmentChangedAt;

    private LocalDateTime firstOrderAt;
    private LocalDateTime lastOrderAt;
    private Integer daysSinceLastOrder;

    /** Usual days between orders (median); distributor-wide median when the shop has too few orders. */
    private Double usualGapDays;
    private Boolean gapIsEstimated;
    private LocalDate nextExpectedOrderDate;

    private Integer ordersAllTime;
    private Integer orders30d;
    private Integer orders90d;

    @Column(precision = 14, scale = 2)
    private BigDecimal revenue30d;
    @Column(precision = 14, scale = 2)
    private BigDecimal revenue90d;

    /** Average order value over 90 days. */
    @Column(precision = 12, scale = 2)
    private BigDecimal avgBasketKes;
    private Double avgLinesPerOrder;

    /** Average order value, last 30 days vs the 30 days before, as a % change. Null when either side is empty. */
    private Double basketTrendPct;

    /** Orders placed by the shop itself (from CRM links) in the last 60 days, and their share. */
    private Integer selfOrders60d;
    private Double selfOrderShare60d;
    private Integer cancellations60d;

    /** Ordering itself regularly with no cancellations — invite to SFA consignment onboarding. */
    private Boolean consignmentReady;

    /** JSON: [{"itemCode","itemName","units","orders"}] — top products over 90 days. */
    @Column(columnDefinition = "TEXT")
    private String topItemsJson;

    /** JSON: [{"itemCode","itemName","shopShare"}] — popular with other shops, not bought here in 90 days. */
    @Column(columnDefinition = "TEXT")
    private String crossSellJson;

    @Column(nullable = false)
    private LocalDateTime computedAt;
}
