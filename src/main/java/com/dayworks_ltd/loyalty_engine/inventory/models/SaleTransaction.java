package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "sale_transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaleTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "sale_datetime", nullable = false)
    private LocalDateTime saleDateTime;

    @Column(name = "item_name", nullable = false)
    private String itemName;
    @Column(name = "order_type", nullable = false)
    private String orderType;

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_cost", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitCost;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "total_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "transaction_ref")
    private String transactionRef;

    @Column(precision = 12, scale = 2)
    private BigDecimal discount;  // per line discount/extra

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now();   // ← THIS WAS MISSING
    }
}