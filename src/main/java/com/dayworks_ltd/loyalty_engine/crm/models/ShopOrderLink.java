package com.dayworks_ltd.loyalty_engine.crm.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A one-time ordering link sent to a shop by SMS/WhatsApp. Whoever opens it orders for that shop,
 * so it is single-use (done once an order is placed) and expires after a few days if unused.
 * Only a hash of the token is stored.
 */
@Entity
@Table(name = "crm_order_link",
        uniqueConstraints = @UniqueConstraint(name = "uk_crm_order_link_token", columnNames = "tokenHash"),
        indexes = @Index(name = "idx_crm_order_link_shop", columnList = "distributorId, shopMerchantId"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopOrderLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Long distributorId;

    @Column(nullable = false)
    private Long shopMerchantId;

    /** The CRM message that carried this link (for attribution). */
    private Long crmMessageId;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime firstOpenedAt;

    @Builder.Default
    @Column(nullable = false)
    private Integer openCount = 0;

    /** Set when an order is placed — the link is then done. */
    private LocalDateTime usedAt;
    private Long orderId;
}
