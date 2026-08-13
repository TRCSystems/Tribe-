package com.dayworks_ltd.loyalty_engine.credit_engine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
public class InvoiceExtractionResponse {

    @JsonProperty("invoiceSubmissionId")
    private String invoiceSubmissionId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("data")
    private InvoiceDataDTO data;

    @JsonProperty("error")
    private String error;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("processedAt")
    private LocalDateTime processedAt;

    @Value
    @Builder
    public static class CreditLimitResponse {
        Long merchantId;
        BigDecimal approvedLimit;
        BigDecimal outstandingBalance;
        BigDecimal availableLimit;
        Integer tradeScore;
        String tradeGrade;
        LocalDateTime lastRecalculatedAt;
    }
}
