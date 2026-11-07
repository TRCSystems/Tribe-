package com.dayworks_ltd.loyalty_engine.crm.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * How a distributor reaches one shop: phone, WhatsApp, preferred channel and marketing opt-out.
 * Created on first use from the shop's business phone.
 */
@Entity
@Table(name = "crm_contact",
        uniqueConstraints = @UniqueConstraint(name = "uk_crm_contact_dist_shop",
                columnNames = {"distributorId", "shopMerchantId"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrmContact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long distributorId;

    @Column(nullable = false)
    private Long shopMerchantId;

    /** SMS number, normalised to 07XXXXXXXX / 01XXXXXXXX. Null when the shop has no valid number. */
    @Column(length = 15)
    private String phone;

    /** WhatsApp number if different from phone. */
    @Column(length = 15)
    private String whatsappNumber;

    /** Person to talk to at the shop (asked on WhatsApp before the shop's first order). */
    @Column(length = 120)
    private String contactName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CrmEnums.Channel preferredChannel = CrmEnums.Channel.SMS;

    /** Shop said STOP: no marketing messages (order updates still go out). */
    @Builder.Default
    @Column(nullable = false)
    private Boolean marketingOptOut = false;

    private LocalDateTime optOutAt;

    /**
     * Last time the shop messaged our WhatsApp number. WhatsApp only allows free-form messages
     * within 24 hours of this; outside it we must use an approved template.
     */
    private LocalDateTime lastWhatsappInboundAt;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
}
