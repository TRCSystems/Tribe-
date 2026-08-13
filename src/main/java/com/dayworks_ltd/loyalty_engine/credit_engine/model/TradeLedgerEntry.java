package com.dayworks_ltd.loyalty_engine.credit_engine.model;



import com.dayworks_ltd.loyalty_engine.common.LedgerEntryType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only. No update or delete operations should ever be exposed for this
 * entity — corrections are new ADJUSTMENT rows, never edits to existing rows.
 * Mirrors the append-only pattern already used for InventoryStockAudit.
 */
@Entity
@Table(name = "trade_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private LedgerEntryType entryType;

    /** Always positive; entryType gives direction (credit vs repayment). */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    /** Bill Manager externalReference / transactionId, for traceability. */
    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 'SYSTEM', 'BILLMANAGER_CALLBACK', or an admin username. */
    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;
}
