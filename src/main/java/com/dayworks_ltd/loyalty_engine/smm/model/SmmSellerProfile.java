package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * How a seller wants the agents to sound and what they may say: voice samples, no-go phrases, where the shop is,
 * opening hours, staff to hand over to. One row per seller.
 */
@Entity
@Table(name = "smm_seller_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_smm_profile_merchant", columnNames = "merchant_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmSellerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** About 20 of the seller's own past replies, one per line: the Writer copies their voice, not their words. */
    @Column(name = "voice_samples", columnDefinition = "TEXT")
    private String voiceSamples;

    /** Words or promises the agents must never use, one per line (e.g. "free delivery", "original"). */
    @Column(name = "no_go_phrases", columnDefinition = "TEXT")
    private String noGoPhrases;

    /** How the seller addresses buyers: dada, bro, formal... */
    @Column(length = 40)
    private String tone;

    /** Languages offered, e.g. "sw,en". */
    @Column(length = 40)
    @Builder.Default
    private String languages = "sw,en";

    /** e.g. "Mon-Sat 8am-7pm, Sun closed". */
    @Column(name = "opening_hours", length = 200)
    private String openingHours;

    /** e.g. "Moi Avenue, Bihi Towers 6th floor, suite 2". */
    @Column(name = "location_text", length = 300)
    private String locationText;

    @Column(name = "map_lat")
    private Double mapLat;

    @Column(name = "map_lng")
    private Double mapLng;

    /** Landmark directions, e.g. "opposite Sonford, take the lift to the 6th floor". */
    @Column(name = "landmark_directions", columnDefinition = "TEXT")
    private String landmarkDirections;

    /** The seller's WhatsApp number for buyers (wa.me links) and for alerts to the seller. */
    @Column(name = "whatsapp_number", length = 20)
    private String whatsappNumber;

    /** Staff who take handovers: "name|phone" per line. */
    @Column(name = "staff_contacts", columnDefinition = "TEXT")
    private String staffContacts;

    /** Language for the seller's own WhatsApp alerts. */
    @Column(name = "alert_language", length = 2)
    @Builder.Default
    private String alertLanguage = "en";

    /** Seller paused the agents (e.g. "PAUSE" on WhatsApp): comments are still read and stored, nothing is sent. */
    @Column(name = "agents_paused", nullable = false)
    @Builder.Default
    private boolean agentsPaused = false;

    /** First days with a new seller: replies are written and logged but not sent (shadow mode). */
    @Column(name = "shadow_mode", nullable = false)
    @Builder.Default
    private boolean shadowMode = true;
    /**
     * What the seller's next WhatsApp message answers, in the morning review: "FAQ:&lt;handover id&gt;" (a buyer's
     * question) or "FIX:&lt;reply id&gt;" (a better wording for a reply). Null = nothing asked.
     */
    @Column(name = "awaiting", length = 40)
    private String awaiting;
    @Column(name = "awaiting_since")
    private LocalDateTime awaitingSince;

    /**
     * The seller's own Lipa na M-Pesa till. Sellers are always paid into their own till; Tribe never collects for them
     * or changes where they are paid. Sellers with only a personal M-Pesa number have no till and get no watermark.
     */
    @Column(name = "till_number", length = 10)
    private String tillNumber;
    /**
     * The business name exactly as M-Pesa shows it when a buyer pays the till, copied from a real KES 10 test payment.
     * This is what the video watermark and the agents tell buyers to check, so it is never the Page name.
     */
    @Column(name = "till_name", length = 60)
    private String tillName;
    /** When our team made the test payment and confirmed {@link #tillName}. Null = not verified, no watermark yet. */
    @Column(name = "till_verified_at")
    private LocalDateTime tillVerifiedAt;

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
