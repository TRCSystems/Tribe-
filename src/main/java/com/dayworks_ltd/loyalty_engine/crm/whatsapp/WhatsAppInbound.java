package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Every message a shop sends to the Tribe WhatsApp number. The unique message id stops Meta's
 * webhook retries from being handled twice (e.g. two M-Pesa prompts for one tap).
 */
@Entity
@Table(name = "crm_whatsapp_inbound",
        uniqueConstraints = @UniqueConstraint(name = "uk_wa_inbound_message", columnNames = "waMessageId"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppInbound {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String waMessageId;

    /** Sender, as WhatsApp gives it (2547XXXXXXXX). */
    @Column(nullable = false, length = 20)
    private String fromNumber;

    /** WhatsApp profile name of the sender. */
    @Column(length = 120)
    private String profileName;

    /** text, interactive, button, image… */
    @Column(length = 20)
    private String type;

    /** Text typed, or the id of the button / list row tapped. */
    @Column(length = 1000)
    private String content;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();
}
