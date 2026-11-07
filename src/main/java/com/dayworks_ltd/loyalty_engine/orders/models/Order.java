package com.dayworks_ltd.loyalty_engine.orders.models;

import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.common.OrderType;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    @Builder.Default
    private OrderType orderType = OrderType.SALE;

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

    @Column(name = "merchant_contribution", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal merchantContribution = BigDecimal.ZERO;

    @Column(name = "financed_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal financedAmount = BigDecimal.ZERO;

    @Column(name = "projected_margin", precision = 12, scale = 2)
    private BigDecimal projectedMargin; // ESTIMATE at order-creation time; never a realized/actual margin

    @Column(name = "margin_coverage_ratio", precision = 6, scale = 4)
    private BigDecimal marginCoverageRatio; // projectedMargin / financedAmount

    @Column(name = "margin_data_complete", nullable = false)
    @Builder.Default
    private boolean marginDataComplete = true;

    /** Who placed the order. Null on orders created before CRM tracking (treat as DISTRIBUTOR). */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_source", length = 20)
    private OrderSource orderSource;

    /** User who placed it (distributor user, field rep, or null for a shop link). */
    @Column(name = "placed_by_user_id")
    private Long placedByUserId;

    /** CRM message (campaign) that led to this order, when placed from a message link. */
    @Column(name = "crm_message_id")
    private Long crmMessageId;

    /** Free-text requests from the shop for items not in the catalogue (shop order link). */
    @Column(name = "shop_note", columnDefinition = "TEXT")
    private String shopNote;

    /** Consumer orders (WhatsApp shop assistant): who ordered and where to deliver. */
    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    /** Why the distributor cancelled the order (required when cancelling). */
    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by_user_id")
    private Long cancelledByUserId;

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