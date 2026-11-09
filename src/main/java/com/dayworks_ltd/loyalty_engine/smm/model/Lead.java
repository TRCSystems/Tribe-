
package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "smm_leads", indexes = {
        @Index(name = "idx_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_sender_psid", columnList = "sender_psid"),
        @Index(name = "idx_platform", columnList = "platform"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    // Unique sender ID from platform (PSID for FB/IG, open_id for TikTok)
    @Column(name = "sender_psid", nullable = false, length = 100)
    private String senderPsid;

    @Column(name = "sender_name", length = 150)
    private String senderName;

    @Column(name = "sender_profile_pic", length = 500)
    private String senderProfilePic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Platform platform;                    // FACEBOOK, INSTAGRAM, TIKTOK

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadSource source;                    // DM, COMMENT, SHOP_CHECKOUT, STORY_MENTION

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeadStatus status = LeadStatus.NEW;   // NEW → CONTACTED → HOT → CONVERTED → LOST

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 100)
    private String email;

    // What they asked about
    @Column(name = "interested_item_name", length = 255)
    private String interestedItemName;

    @Column(name = "interested_item_code", length = 100)
    private String interestedItemCode;

    @Column(name = "estimated_value", precision = 12, scale = 2)
    private BigDecimal estimatedValue;

    // Conversation
    @Column(name = "first_message", columnDefinition = "TEXT")
    private String firstMessage;

    @Column(name = "latest_message", columnDefinition = "TEXT")
    private String latestMessage;

    @Column(name = "message_count")
    private Integer messageCount = 1;

    // AI scoring
    @Column(name = "intent_score")
    private Double intentScore;                   // 0.0 – 1.0 (from AI analysis)

    @Column(name = "ai_tags", length = 500)
    private String aiTags;                        // e.g. "size inquiry, urgent, price sensitive"

    // SokoReply: how close to buying, which Reel brought them, whether they are in a delivery zone
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Temperature temperature;

    @Column(name = "post_id", length = 64)
    private String postId;

    /** True when the place they gave is in one of the seller's delivery zones; null = no place given. */
    @Column(name = "zone_match")
    private Boolean zoneMatch;
    /** Just became hot on a live comment: the seller's WhatsApp alert hasn't gone out yet. */
    @Column(name = "hot_alert_pending")
    private Boolean hotAlertPending;
    @Column(name = "hot_alerted_at")
    private LocalDateTime hotAlertedAt;
    /** What they want, for the alert: "black, size 12", "to book". */
    @Column(name = "hot_want", length = 120)
    private String hotWant;

    // Timestamps
    @Column(name = "first_contact_at", nullable = false)
    private LocalDateTime firstContactAt = LocalDateTime.now();

    @Column(name = "last_contact_at", nullable = false)
    private LocalDateTime lastContactAt = LocalDateTime.now();

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        // The builder skips the field defaults above; these columns are NOT NULL
        if (this.firstContactAt == null) this.firstContactAt = this.createdAt;
        if (this.lastContactAt == null) this.lastContactAt = this.createdAt;
        if (this.status == null) this.status = LeadStatus.NEW;
        if (this.messageCount == null) this.messageCount = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        this.lastContactAt = LocalDateTime.now();
    }

    // Reusable enums
    public enum Platform {
        FACEBOOK, INSTAGRAM, TIKTOK
    }

    public enum LeadSource {
        DM, COMMENT, SHOP_CHECKOUT, STORY_MENTION, REELS_TAG
    }

    public enum LeadStatus {
        NEW, CONTACTED, HOT, FOLLOWED_UP, CONVERTED, LOST, SPAM
    }

    /** COLD: praise or no buying sign; WARM: asked price, size or place; HOT: wants it now, left a number, or asked to be called. */
    public enum Temperature {
        COLD, WARM, HOT
    }
}