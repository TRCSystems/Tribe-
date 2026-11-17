package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One message in the Messenger or Instagram chat that follows a private reply: the buyer's, or the agent's answer.
 * replyId is the private reply that opened the chat, so a whole conversation is every row with the same replyId.
 */
@Entity
@Table(name = "smm_chat_message",
        indexes = {
                @Index(name = "idx_smm_chat_thread", columnList = "reply_id, id"),
                @Index(name = "idx_smm_chat_status", columnList = "status, id"),
                @Index(name = "idx_smm_chat_mid", columnList = "meta_message_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmChatMessage {

    /** RECEIVED = a buyer's message waiting for an answer; ANSWERED = handled; SENT/FAILED/SKIPPED = the agent's side. */
    public enum Status { RECEIVED, ANSWERED, SENT, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "reply_id", nullable = false)
    private Long replyId;

    @Column(name = "comment_id")
    private Long commentId;

    /** The ID Meta gives the buyer for this Page; messages are sent back to it. */
    @Column(name = "buyer_id", nullable = false, length = 100)
    private String buyerId;

    @Column(name = "from_buyer", nullable = false)
    private boolean fromBuyer;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(name = "meta_message_id", length = 200)
    private String metaMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    /** True on the agent's message that passed the chat to the seller; the agents stay quiet after it. */
    @Builder.Default
    @Column(nullable = false)
    private boolean handover = false;

    /** Why a message was skipped, failed or handed over. */
    @Column(length = 300)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
