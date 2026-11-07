package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** An area the seller delivers to (Nairobi CBD, Kenya by courier, East Africa), with the fee and how long it takes. */
@Entity
@Table(name = "smm_delivery_zone", indexes = @Index(name = "idx_smm_zone_merchant", columnList = "merchant_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmDeliveryZone {

    public enum Method { PICKUP, RIDER, BUS_PARCEL, COURIER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Places that count as this zone, comma separated (e.g. "CBD, Ngara, Upper Hill"), for matching "niko ...". */
    @Column(length = 500)
    private String places;

    @Column(precision = 10, scale = 2)
    private BigDecimal fee;

    /** e.g. "same day", "1-2 days". */
    @Column(length = 40)
    private String days;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private Method method = Method.RIDER;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
