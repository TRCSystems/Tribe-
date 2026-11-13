package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The public ordering page for one Reel: every buyer who comments on that post, and every reply the agents send,
 * shares the same link ("tribessystems.co.ke/s/{token}"), unlike a one-time shop link. Which specific comment led to
 * an order is recorded on the {@code Order} itself (socialCommentId), passed at order time, not by the link.
 * Unlike {@code ShopOrderLink}'s one-time token, this one is meant to be public and reused — it is handed to every
 * commenter on the post — so the token itself is stored in the clear; nothing secret turns on it being hard to guess.
 */
@Entity
@Table(name = "smm_order_link",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_smm_order_link_token", columnNames = "token"),
                @UniqueConstraint(name = "uk_smm_order_link_post", columnNames = {"merchantId", "postId"})
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmOrderLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String token;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 64)
    private String postId;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private Integer openCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer orderCount = 0;
}
