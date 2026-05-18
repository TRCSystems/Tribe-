package com.dayworks_ltd.loyalty_engine.inventory.models;


import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stock_transfer_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_transfer_id", nullable = false)
    private StockTransfer stockTransfer;

    @Column(nullable = false)
    private String itemCode;

    @Column(nullable = false)
    private String itemName;

    @Column(nullable = false)
    private Integer quantityIssued;

    private Integer quantityReceived;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal wholesaleUnitPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Column(columnDefinition = "TEXT")
    private String remarks;
}