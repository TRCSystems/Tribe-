package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Every change to an item's unit cost, selling price or wholesale price, with who made it.
 * Price edits are a quiet way to lose money (sell below cost, pocket the difference), so the owner
 * can always see what changed and when.
 */
@Entity
@Table(name = "inventory_price_change", indexes = {
        @Index(name = "idx_price_change_item", columnList = "merchantId, inventoryId, changedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceChange {

    public enum Field { UNIT_COST, UNIT_PRICE, WHOLESALE_PRICE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String merchantId;

    @Column(nullable = false)
    private Long inventoryId;

    @Column(length = 100)
    private String itemCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Field field;

    /** Null when there was no value before (e.g. first wholesale price). */
    @Column(precision = 12, scale = 2)
    private BigDecimal oldValue;

    /** Null when the value was removed (wholesale price cleared). */
    @Column(precision = 12, scale = 2)
    private BigDecimal newValue;

    private Long changedByUserId;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();
}
