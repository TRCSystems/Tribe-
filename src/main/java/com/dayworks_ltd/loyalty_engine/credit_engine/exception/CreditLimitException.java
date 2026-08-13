package com.dayworks_ltd.loyalty_engine.credit_engine.exception;

public class CreditLimitException extends RuntimeException {
    public CreditLimitException(String message) {
        super(message);
    }
}