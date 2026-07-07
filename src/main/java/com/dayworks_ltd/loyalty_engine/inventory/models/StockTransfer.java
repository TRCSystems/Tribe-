package com.dayworks_ltd.loyalty_engine.inventory.models;

import com.dayworks_ltd.loyalty_engine.auth.enums.TransferStatus;
import com.dayworks_ltd.loyalty_engine.auth.enums.TransferType;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stock_transfers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transferCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Merchant distributor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Merchant recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferType transferType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(nullable = false)
    private LocalDateTime issueDate;

    private LocalDateTime receivedDate;

    private Long issuedByUserId;
    private Long receivedByUserId;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalWholesaleAmount;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @OneToMany(mappedBy = "stockTransfer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockTransferItem> items = new ArrayList<>();

    public void addItem(StockTransferItem item) {
        this.items.add(item);
        item.setStockTransfer(this);
    }
}