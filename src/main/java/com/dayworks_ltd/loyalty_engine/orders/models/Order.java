package com.dayworks_ltd.loyalty_engine.orders.models;

import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Merchant distributor;

    @Column(nullable = false)
    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMode paymentMode;

    private LocalDateTime paymentDate;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    private String phoneNumber;

    @Column(name = "checkout_request_id")
    private String checkoutRequestId;

    private String paymentReference;

    //  CRITICAL FIX: Initialize the list
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_transfer_id")
    private StockTransfer stockTransfer;

    @Column(name = "fulfilled_date")
    private LocalDateTime fulfilledDate;

    @Column(name = "received_date")
    private LocalDateTime receivedDate;

    // Add this helper method
    public void markAsFulfilled(StockTransfer stockTransfer) {
        this.stockTransfer = stockTransfer;
        this.fulfilledDate = LocalDateTime.now();
        this.status = OrderStatus.FULFILLED;
    }

    public void markAsReceived() {
        this.status = OrderStatus.RECEIVED;
        this.receivedDate = LocalDateTime.now();
    }

    // Helper method to safely add items
    public void addItem(OrderItem item) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(item);
        item.setOrder(this);
    }
}