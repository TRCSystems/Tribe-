package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "wholesale_price_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WholesalePriceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String itemCode;          // links to Inventory.itemCode

    @Column(nullable = false)
    private String merchantId;        // scope to merchant (null = global)

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal wholesalePrice;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column
    private LocalDate effectiveTo;    // null = open-ended / currently active

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime updatedAt;
}