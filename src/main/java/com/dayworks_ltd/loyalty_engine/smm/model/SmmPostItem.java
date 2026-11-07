package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A product, service or set shown on a post: what "how much?" on that Reel refers to. */
@Entity
@Table(name = "smm_post_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_smm_post_item", columnNames = {"merchant_id", "post_id", "item_code"}),
        indexes = @Index(name = "idx_smm_post_item_post", columnList = "merchant_id, post_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmPostItem {

    public enum TaggedBy { SELLER, SUGGESTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Platform post (Reel) ID. */
    @Column(name = "post_id", nullable = false, length = 64)
    private String postId;

    /** Catalogue item code (inventory_stock.item_code); for a set, the set's own code. */
    @Column(name = "item_code", nullable = false, length = 100)
    private String itemCode;

    @Column(name = "set_id")
    private Long setId;

    /** Price said or written in the video, if any: the agents warn the seller when it differs from the catalogue. */
    @Column(name = "price_shown", precision = 12, scale = 2)
    private BigDecimal priceShown;

    /** Tagged by the seller, or suggested by the agents from the caption and still to be confirmed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tagged_by", nullable = false, length = 12)
    @Builder.Default
    private TaggedBy taggedBy = TaggedBy.SELLER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
