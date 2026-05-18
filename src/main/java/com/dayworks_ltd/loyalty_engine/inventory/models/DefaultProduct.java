package com.dayworks_ltd.loyalty_engine.inventory.models;

import com.dayworks_ltd.loyalty_engine.auth.enums.BusinessType;
import jakarta.persistence.*;
import lombok.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_defaults")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "product_code", nullable = false, unique = true, length = 6)
    private String productCode;

    @Column(name = "volume_ml", nullable = false)
    private Integer volume_Ml;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false)
    private BusinessType businessType;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        if (productCode == null || productCode.isBlank()) {
            productCode = generateCode();
        }
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private String generateCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
