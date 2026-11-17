package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One item a seller advertised in a post's own caption, for the sellers who never tag a catalogue product (most of
 * them): just a name and a price lifted from what they wrote. Builds up into that seller's range across their posts,
 * so a buyer's budget or a different item than the one they commented on can still be answered.
 */
@Entity
@Table(name = "smm_caption_item",
        indexes = {
                @Index(name = "idx_smm_caption_item_merchant", columnList = "merchant_id, id"),
                @Index(name = "idx_smm_caption_item_post", columnList = "merchant_id, post_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmCaptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "post_id", nullable = false, length = 64)
    private String postId;

    @Column(name = "item_name", nullable = false, length = 200)
    private String name;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    /** Size, colour or other detail as the caption wrote it, in one line. */
    @Column(length = 300)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
