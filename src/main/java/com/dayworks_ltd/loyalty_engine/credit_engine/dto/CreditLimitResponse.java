package com.dayworks_ltd.loyalty_engine.credit_engine.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class CreditLimitResponse {
    Long merchantId;
    BigDecimal approvedLimit;
    BigDecimal outstandingBalance;
    BigDecimal availableLimit;
    Integer tradeScore;
    String tradeGrade;
    LocalDateTime lastRecalculatedAt;
}