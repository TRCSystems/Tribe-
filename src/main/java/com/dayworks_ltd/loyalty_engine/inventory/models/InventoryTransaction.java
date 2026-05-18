package com.dayworks_ltd.loyalty_engine.inventory.models;



import com.dayworks_ltd.loyalty_engine.auth.enums.TransactionType;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_transactions",
        indexes = {
                @Index(name = "idx_merchant_item", columnList = "merchant_id, item_code"),
                @Index(name = "idx_transaction_date", columnList = "transaction_date"),
                @Index(name = "idx_reference", columnList = "reference_id, reference_type")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionCode;        // e.g., IT-20260501-00123

//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "merchant_id", nullable = false)
//    private Merchant merchant;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String itemCode;

    @Column(nullable = false)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType transactionType;

    @Column(nullable = false)
    private Integer quantity;                 // Positive or Negative

    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalAmount;

    // Reference to source document
    private Long referenceId;                 // e.g., StockTransfer.id, Sale.id, etc.

    @Column(length = 50)
    private String referenceType;             // "STOCK_TRANSFER", "SALE", "ADJUSTMENT", "COUNT"

    // Stock state before this transaction
    private Integer previousAvailableStock;
    private Integer previousAddedStock;

    // Who performed this action
    private Long performedByUserId;
    private Long performedByMerchantId;       // In case admin or distributor acts on behalf of merchant

    @Column(nullable = false)
    private LocalDateTime transactionDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (this.transactionDate == null) {
            this.transactionDate = LocalDateTime.now();
        }
        if (this.transactionCode == null) {
            this.transactionCode = "IT-" + System.currentTimeMillis();
        }
    }
}