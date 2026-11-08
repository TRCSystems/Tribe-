package com.dayworks_ltd.loyalty_engine.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CreateMerchantRequest {
    // merchant fields
    @NotBlank
    private String businessName;
    @NotBlank
    private String location;
    @NotBlank
    private String tillNumber;
    @NotBlank
    private String businessType;
    private String businessPhone;

    // user fields
    private Boolean isWholesaler = false;
    private String subscriptionPlan;

    /** FULL (default) or SOKOREPLY, see MerchantPlan. Ignored when modules are given. */
    private String plan;

    /** Modules bought (POS, ORDERS, INVENTORY, CUSTOMERS, REPORTS, FINANCE, SOKOREPLY), see MerchantModule. */
    private java.util.List<String> modules;
}