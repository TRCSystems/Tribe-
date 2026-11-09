package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One thing the agents did about a comment: a public reply, a private reply (DM), or hiding it. */
@Entity
@Table(name = "smm_reply",
        indexes = {
                @Index(name = "idx_smm_reply_comment", columnList = "comment_id"),
                @Index(name = "idx_smm_reply_merchant", columnList = "merchant_id, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmReply {

    public enum Kind { PUBLIC, PRIVATE, HIDE }

    /** LOGGED = written but not sent (shadow mode or agents paused). */
    public enum Status { PENDING, SENT, LOGGED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** smm_comment.id */
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(columnDefinition = "TEXT")
    private String text;

    /** Which angle the Writer used (price first, colour, location...), so replies on one Reel vary. */
    @Column(length = 40)
    private String angle;

    @Column(length = 40)
    private String model;

    /** The Checker's verdict: PASS, or why it failed. */
    @Column(name = "checker_verdict", length = 200)
    private String checkerVerdict;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.PENDING;

    /** ID Meta gave the reply or message once sent. */
    @Column(name = "meta_message_id", length = 100)
    private String metaMessageId;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** What the seller changed it to in the morning review, if anything: used to teach the voice. */
    @Column(name = "seller_correction", columnDefinition = "TEXT")
    private String sellerCorrection;
    /** When the seller looked at it in the morning review (good, or fixed). */
    @Column(name = "seller_reviewed_at")
    private LocalDateTime sellerReviewedAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    // ── did the buyer answer? (private replies only) ──
    // Meta allows one private reply per comment; only the buyer's answer opens a chat. These fields give the reply rate.

    /**
     * Who the private reply went to: the commenter's ID at first; the sender replaces it with the recipient ID Meta
     * returns, which is the ID the buyer's messages arrive with.
     */
    @Column(name = "recipient_id", length = 100)
    private String recipientId;

    /** When the buyer first wrote back after this private reply. Null = no answer (yet). */
    @Column(name = "buyer_replied_at")
    private LocalDateTime buyerRepliedAt;

    /** The buyer's first answer, so the seller sees how the question landed. */
    @Column(name = "buyer_reply_text", columnDefinition = "TEXT")
    private String buyerReplyText;

    /** The buyer's latest message: the chat window is open for 24 hours after it. */
    @Column(name = "last_buyer_message_at")
    private LocalDateTime lastBuyerMessageAt;

    @Column(name = "buyer_message_count")
    @Builder.Default
    private Integer buyerMessageCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
