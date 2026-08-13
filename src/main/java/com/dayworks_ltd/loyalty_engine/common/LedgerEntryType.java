package com.dayworks_ltd.loyalty_engine.common;

public enum LedgerEntryType {
    CREDIT_ISSUED,      // consignment order created — increases merchant's outstanding balance
    PAYMENT_RECEIVED,   // full or partial repayment — decreases outstanding balance
    ADJUSTMENT,         // manual correction — must always have a reason logged
    LIMIT_CHANGE        // credit limit recalculated (audit trail of score-driven limit moves)
}