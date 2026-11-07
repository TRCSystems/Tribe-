package com.dayworks_ltd.loyalty_engine.crm.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Which field rep looks after a shop for a distributor (one rep per shop). */
@Entity
@Table(name = "crm_rep_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_rep_assignment_dist_shop",
                columnNames = {"distributorId", "shopMerchantId"}),
        indexes = @Index(name = "idx_rep_assignment_rep", columnList = "repUserId"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long distributorId;

    @Column(nullable = false)
    private Long shopMerchantId;

    @Column(nullable = false)
    private Long repUserId;

    private Long assignedByUserId;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();
}
