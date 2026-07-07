package com.dayworks_ltd.loyalty_engine.orders.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantSummaryDTO {
    private Long id;
    private String businessName;
    private String businessType;
    private String location;
    private String tillNumber;
    private String businessPhone;
}