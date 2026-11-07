package com.dayworks_ltd.loyalty_engine.crm.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Every CRM message, including the ones deliberately not sent (SKIPPED with a reason).
 * Orders placed from a message link point back here (orders.crm_message_id) for attribution.
 */
@Entity
@Table(name = "crm_message", indexes = {
        @Index(name = "idx_crm_message_shop", columnList = "distributorId, shopMerchantId, createdAt"),
        @Index(name = "idx_crm_message_campaign", columnList = "campaignKey")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrmMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long distributorId;

    @Column(nullable = false)
    private Long shopMerchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CrmTemplate template;

    /** Which automation / campaign run sent it, e.g. "WEEKEND_STOCKUP:2026-10-15" or "MANUAL". */
    @Column(length = 80)
    private String campaignKey;

    @Column(nullable = false)
    private Boolean marketing;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private CrmEnums.Channel channel;

    @Column(length = 15)
    private String toNumber;

    @Column(length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CrmEnums.MessageStatus status;

    @Column(length = 120)
    private String skipReason;

    /** Provider's id for the message (WhatsApp wamid) so delivery receipts can find it. */
    @Column(length = 128)
    private String providerMessageId;

    /** Latest receipt from the provider: delivered, read or failed (WhatsApp only). */
    @Column(length = 12)
    private String deliveryStatus;

    /** userId of the person who triggered it; null for automations. */
    private Long sentByUserId;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
