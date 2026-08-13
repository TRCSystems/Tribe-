package com.dayworks_ltd.loyalty_engine.credit_engine.exception;


import java.math.BigDecimal;

public class ReserveCapacityExceededException extends RuntimeException {

    public ReserveCapacityExceededException(BigDecimal requested, BigDecimal available) {
        super(String.format(
                "Consignment of %s requested but only %s remains in the reserve — cannot lend beyond reserve capacity",
                requested, available));
    }
}
