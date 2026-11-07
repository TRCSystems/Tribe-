package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A WhatsApp shopper's draft order at one store. A draft is not an order: nothing is created until
 * the customer confirms, and prices/stock are rechecked at that moment. One open cart per phone + store.
 */
@Entity
@Table(name = "whatsapp_shop_cart", indexes = @Index(name = "idx_shop_cart_phone", columnList = "phone, storeMerchantId, status"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopCart {

    public enum Status {
        /** Collecting items. */
        OPEN,
        /** Customer confirmed; waiting for a delivery address. */
        AWAITING_ADDRESS,
        /** B2B: a new shop confirmed; waiting for its shop / business name. */
        AWAITING_SHOP_NAME,
        /** B2B: waiting for the shop's contact person. */
        AWAITING_CONTACT,
        /** Order created (see orderId). */
        ORDERED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** WhatsApp number, 2547XXXXXXXX. */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false)
    private Long storeMerchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.OPEN;

    /** Items as JSON: [{"code":"…","qty":2}] — prices are never stored here, they're read live. */
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String itemsJson = "[]";

    /** Questions still to ask, as JSON: [{"requested":"maziwa","qty":3,"options":["code1","code2"]}]. */
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String questionsJson = "[]";

    /** Item the customer picked while browsing, waiting for a quantity. */
    @Column(length = 100)
    private String pendingItemCode;

    @Column(length = 120)
    private String customerName;

    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;

    /** B2B: shop / business name a new shop gave before its first order. */
    @Column(length = 120)
    private String shopName;

    /** B2B: an existing shop this order is for, matched from the shop name on a photographed list or receipt. */
    private Long shopMerchantId;

    /** Items that were in the cart when a photo started a new order, kept so they can be added back with one tap. */
    @Column(columnDefinition = "TEXT")
    private String previousItemsJson;

    /** B2B: a shop name read from a photo that isn't one of the distributor's shops (confirmed at checkout). */
    @Column(length = 120)
    private String photoShopName;

    /** B2B: contact person the shop gave for this order. */
    @Column(length = 120)
    private String contactPerson;

    private Long orderId;

    @Column(length = 50)
    private String orderCode;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
