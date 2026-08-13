package com.dayworks_ltd.loyalty_engine.credit_engine.model;



import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Every callback — successful, failed, duplicate, malformed — gets logged
 * here raw, before any parsing. Never conditional, never filtered.
 */
@Entity
@Table(name = "raw_callback_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RawCallbackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 'STK_CALLBACK' or 'BILLMANAGER_RECONCILIATION'. */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    @Builder.Default
    private LocalDateTime receivedAt = LocalDateTime.now();

    @Column(name = "processed", nullable = false)
    @Builder.Default
    private boolean processed = false;

    @Column(name = "order_id")
    private Long orderId; // nullable — set once matched
}
