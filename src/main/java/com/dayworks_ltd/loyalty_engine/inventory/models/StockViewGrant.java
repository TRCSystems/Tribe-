package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Owner verification for viewing stock quantities.
 * An OTP is SMS'd to the merchant's registered phone; when verified, a short-lived
 * token is issued to that browser only. Only hashes are stored.
 */
@Entity
@Table(name = "stock_view_grant", indexes = {
        @Index(name = "idx_stock_view_user", columnList = "userId"),
        @Index(name = "idx_stock_view_token", columnList = "tokenHash")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockViewGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 64)
    private String otpHash;

    @Column(nullable = false)
    private LocalDateTime otpExpiresAt;

    @Builder.Default
    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(length = 64)
    private String tokenHash;

    private LocalDateTime tokenExpiresAt;

    private LocalDateTime verifiedAt;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
