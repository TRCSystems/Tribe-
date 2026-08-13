package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import java.math.BigDecimal;

public class RetailPaymentRequest {
    private String phoneNumber;
    private BigDecimal amount;

    // getters and setters
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}