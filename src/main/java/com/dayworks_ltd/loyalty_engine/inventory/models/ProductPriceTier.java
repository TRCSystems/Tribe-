package com.dayworks_ltd.loyalty_engine.inventory.models;

import com.dayworks_ltd.loyalty_engine.auth.enums.PriceType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_price_tiers",
        uniqueConstraints = {
                @UniqueConstraint(
                        columnNames = {"item_code", "merchant_id", "price_type", "min_quantity"}
                )
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Links back to Inventory by itemCode + merchantId
    // NOT a FK to Inventory.id — because inventory rows are batch-scoped
    // same product can have multiple batch rows
    @Column(name = "item_code", nullable = false)
    private String itemCode;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false)
    private PriceType priceType; // RETAIL, WHOLESALE, STAFF, PROMO

    @Column(name = "min_quantity", nullable = false)
    private Integer minQuantity = 1; // min qty to qualify for this tier

    @Column(name = "unit_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "valid_from")
    private LocalDate validFrom; // null = always valid

    @Column(name = "valid_until")
    private LocalDate validUntil; // null = no expiry

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}