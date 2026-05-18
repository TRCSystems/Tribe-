package com.dayworks_ltd.loyalty_engine.credit_engine.dto;

import lombok.Data;

@Data
public class KraCheckerResponse {
    private Integer responseCode;
    private String responseDesc;
    private String status;
    private KraInvoiceDetails invoiceDetails;
}