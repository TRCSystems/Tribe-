package com.dayworks_ltd.loyalty_engine.credit_engine.dto;
import lombok.Value;
import java.math.BigDecimal;

@Value
public class AllocateLimitRequest {
    BigDecimal approvedLimit; //
}