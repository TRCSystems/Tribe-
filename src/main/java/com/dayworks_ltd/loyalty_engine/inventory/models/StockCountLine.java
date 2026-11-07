package com.dayworks_ltd.loyalty_engine.inventory.models;

import com.dayworks_ltd.loyalty_engine.inventory.enums.CountTrigger;
import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.enums.VarianceReason;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One item to be blind-counted in a {@link CloseDay} session.
 * expectedQuantity is snapshotted from the system at the moment the count is submitted
 * and is never returned to SALES_PERSON users.
 */
@Entity
@Table(name = "stock_count_line", indexes = {
        @Index(name = "idx_count_line_close_day", columnList = "closeDayId"),
        @Index(name = "idx_count_line_merchant_item", columnList = "merchantId, itemCode"),
        @Index(name = "idx_count_line_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockCountLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long closeDayId;

    @Column(nullable = false)
    private String merchantId;

    private Long inventoryId;

    @Column(nullable = false, length = 100)
    private String itemCode;

    @Column(nullable = false, length = 255)
    private String itemName;

    /** Position in the counting queue — 1 is counted first (fastest moving). */
    private Integer priorityRank;

    /** Units sold on the trading date when the queue was built. */
    private Integer unitsSold;

    /** System quantity at the moment the final count was submitted. Hidden from counters. */
    private Integer expectedQuantity;

    /** First blind count. If it disagrees with the system, a recount is requested. */
    private Integer firstCount;

    /** Final accepted count (first count if it matched, otherwise the recount). */
    private Integer physicalQuantity;

    /** physicalQuantity - expectedQuantity. Negative = missing stock. */
    private Integer varianceQuantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** varianceQuantity x unitCost. */
    @Column(precision = 14, scale = 2)
    private BigDecimal varianceValue;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockCountStatus status = StockCountStatus.PENDING;

    private String countedBy;           // userId of the counter
    private LocalDateTime countedAt;
    private String classifiedBy;        // userId of the owner/admin who reviewed it
    private LocalDateTime classifiedAt;

    // "trigger" is a reserved word in MySQL, so the column is renamed
    @Enumerated(EnumType.STRING)
    @Column(name = "count_trigger", nullable = false, length = 30)
    private CountTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private VarianceReason varianceReason;

    @Column(length = 1000)
    private String varianceNote;

    /** Why the line was queued, e.g. "Forced: uncounted for 8 days". */
    @Column(length = 255)
    private String queueReason;

    /** Set when the owner chose to write the variance into the stock figures. */
    private Boolean stockAdjusted;

    private String evidencePhotoUrl;

    @Builder.Default
    private Boolean materialVariance = false;

    private String requestedBy;
    private LocalDateTime requiredBy;
}
