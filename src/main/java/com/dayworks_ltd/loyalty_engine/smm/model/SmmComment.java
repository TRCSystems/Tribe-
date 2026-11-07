package com.dayworks_ltd.loyalty_engine.smm.model;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A comment received on a seller's post, with what the Reader understood and where it is in the pipeline.
 * The platform comment ID is unique, so a webhook delivered twice is stored once.
 */
@Entity
@Table(name = "smm_comment",
        uniqueConstraints = @UniqueConstraint(name = "uk_smm_comment_id", columnNames = {"platform", "comment_id"}),
        indexes = {
                @Index(name = "idx_smm_comment_merchant", columnList = "merchant_id, received_at"),
                @Index(name = "idx_smm_comment_post", columnList = "merchant_id, post_id"),
                @Index(name = "idx_smm_comment_state", columnList = "state")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmComment {

    public enum State { NEW, READ, PLANNED, SENT, HANDED_OVER, SKIPPED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialPlatform platform;

    /** The platform's comment ID. */
    @Column(name = "comment_id", nullable = false, length = 100)
    private String commentId;

    @Column(name = "post_id", length = 64)
    private String postId;

    /** Set when this is a reply inside a thread. */
    @Column(name = "parent_comment_id", length = 100)
    private String parentCommentId;

    @Column(name = "commenter_id", length = 100)
    private String commenterId;

    @Column(name = "commenter_name", length = 200)
    private String commenterName;

    @Column(columnDefinition = "TEXT")
    private String text;

    /** When the platform says the comment was written (Meta allows a private reply for 7 days after). */
    @Column(name = "comment_created_at")
    private LocalDateTime commentCreatedAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    // ── What the Reader understood ──

    @Column(length = 20)
    private String intent;

    @Column(name = "product_code", length = 100)
    private String productCode;

    @Column(length = 40)
    private String colour;

    @Column(length = 20)
    private String size;

    @Column(length = 8)
    private String language;

    @Column(length = 8)
    private String confidence;

    private Boolean buyer;

    @Column(name = "wants_whatsapp")
    private Boolean wantsWhatsApp;

    /** A harmful comment (complaint, fraud accusation, insult): hidden from the public and sent to the seller as urgent. */
    private Boolean negative;

    /** Polite or fair criticism: stays visible, counted in the morning summary so the seller sees how it lands. */
    private Boolean criticism;

    @Column(name = "phone_found")
    private Boolean phoneFound;

    /** Read again by the stronger model because the first reading was a doubtful "not a buyer". */
    @Column(name = "second_look")
    private Boolean secondLook;

    @Column(name = "lead_id")
    private Long leadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private State state = State.NEW;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** Copies the Reader's answer onto the comment and moves it to READ. */
    public void applyReading(CommentReader.Reading r, boolean phone, boolean reReadByStrongerModel) {
        if (r == null) return;
        intent = r.intent() == null ? null : r.intent().name();
        productCode = r.productCode();
        colour = r.colour();
        size = r.size();
        language = r.language();
        confidence = r.confidence();
        buyer = r.buyer();
        wantsWhatsApp = r.wantsWhatsApp();
        negative = r.negative();
        criticism = r.criticism();
        phoneFound = phone;
        secondLook = reReadByStrongerModel;
        state = State.READ;
    }
}
