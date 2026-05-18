package com.dayworks_ltd.loyalty_engine.auth.model;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.enums.Status;
import com.dayworks_ltd.loyalty_engine.customers.Customer;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Data
@Setter
@Getter
@Builder
public class User {

    @Id
    @GeneratedValue( strategy = GenerationType.IDENTITY )
    private Long userId;
    @Column(nullable = false)
    private String username;
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    private Status status;

    // Temporary compatibility shim - remove once all 22 references are migrated
    public String getMerchantId() {
        return this.merchant != null ? this.merchant.getId().toString() : null;
    }


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = true)
    private Merchant merchant; // proper FK relationship

    // Getter and Setter
    // Add this field to your User entity
    @Column(name = "subscription_plan")
    private String subscriptionPlan;   // e.g. "RETAIL", "WHOLESALE", "PREMIUM", "BUSINESS"

    @Column(name = "is_wholesaler", nullable = false)
    private Boolean isWholesaler = false;

}
