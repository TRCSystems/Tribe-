package com.dayworks_ltd.loyalty_engine.credit_engine.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KraInvoiceDetails {
    private String supplierPIN;
    private String supplierName;
    private String deviceSerialNumber;
    private String controlUnitInvoiceNumber;
    private String traderSystemInvoiceNumber;
    private BigDecimal totalInvoiceAmount;
    private BigDecimal totalTaxAmount;

    private String salesDate;
    // Add more fields as needed
}