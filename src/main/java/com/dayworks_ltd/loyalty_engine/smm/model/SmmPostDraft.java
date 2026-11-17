package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A post a seller is building from WhatsApp: one or more photos, collected while they're sent, finished off by the
 * description text that follows them and turned into a caption. One of these exists per seller at a time; it's
 * either being collected, posted, or a record of the last post (so "take it down" and "edit: ..." know what to act on).
 */
@Entity
@Table(name = "smm_post_draft", indexes = @Index(name = "idx_smm_post_draft_merchant", columnList = "merchant_id, id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmPostDraft {

    public enum Status { COLLECTING, POSTED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** The WhatsApp media IDs collected so far, comma-separated, oldest first. */
    @Column(name = "media_ids", length = 2000)
    private String mediaIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    /** What the seller typed to describe the item; kept for the caption writer and for an "edit:" correction later. */
    @Column(name = "seller_text", columnDefinition = "TEXT")
    private String sellerText;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(name = "page_post_id", length = 100)
    private String pagePostId;

    @Column(name = "permalink", length = 500)
    private String permalink;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
