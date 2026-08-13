package com.dayworks_ltd.loyalty_engine.credit_engine.model;



import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "reserve_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReserveAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_name", nullable = false, length = 50)
    @Builder.Default
    private String sourceName = "ASANO_SELF_FUNDED"; // later: "PAYA", "BANK_X"

    @Column(name = "total_reserve", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalReserve;

    @Column(name = "allocated", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal allocated = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    public BigDecimal getAvailable() {
        return totalReserve.subtract(allocated);
    }

    @PreUpdate
    @PrePersist
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
