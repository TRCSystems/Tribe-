package com.dayworks_ltd.loyalty_engine.credit_engine.exception;

public class InsufficientCreditException extends RuntimeException {
    public InsufficientCreditException(String message) {
        super(message);
    }
}
