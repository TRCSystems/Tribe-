package com.dayworks_ltd.loyalty_engine.credit_engine.exception;



import java.math.BigDecimal;

public class CreditLimitExceededException extends RuntimeException {

    public CreditLimitExceededException(Long merchantId, BigDecimal requested, BigDecimal available) {
        super(String.format(
                "Merchant %d requested consignment of %s but only %s is available on their credit limit",
                merchantId, requested, available));
    }
}
