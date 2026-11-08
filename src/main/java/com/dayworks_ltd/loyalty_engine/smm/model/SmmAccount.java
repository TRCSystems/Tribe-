package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** A seller's Facebook Page or Instagram account connected to SokoReply. */
@Entity
@Table(name = "smm_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_smm_account_page", columnNames = {"platform", "page_id"}),
        indexes = @Index(name = "idx_smm_account_merchant", columnList = "merchant_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmAccount {

    public enum Status { ACTIVE, TOKEN_EXPIRED, DISCONNECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialPlatform platform;

    /** Facebook Page ID or Instagram business account ID. */
    @Column(name = "page_id", nullable = false, length = 64)
    private String pageId;

    @Column(name = "page_name", length = 200)
    private String pageName;

    /** Long-lived Page access token, stored encrypted; never logged. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "access_token", columnDefinition = "TEXT")
    @ToString.Exclude
    private String accessToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "token_checked_at")
    private LocalDateTime tokenCheckedAt;

    @Column(name = "connected_by_user_id")
    private Long connectedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "last_webhook_at")
    private LocalDateTime lastWebhookAt;

    /** When the last 30 days of comments were fetched after connecting. Null = still to do. */
    @Column(name = "backfilled_at")
    private LocalDateTime backfilledAt;

    /** Why the backfill did not finish, if it failed. */
    @Column(name = "backfill_error", length = 500)
    private String backfillError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
