package com.dayworks_ltd.loyalty_engine.auth.enums;


public enum TransactionType {

    // Stock In
    OPENING_BALANCE,
    PURCHASE_FROM_SUPPLIER,
    STOCK_TRANSFER_RECEIVED,        // Merchant receives from Distributor

    // Stock Out
    SALE,
    STOCK_TRANSFER_ISSUED,          // Distributor issues to Merchant
    DAMAGE,
    LOSS,
    RETURN_TO_SUPPLIER,

    // Adjustments
    MANUAL_ADJUSTMENT_INCREASE,
    MANUAL_ADJUSTMENT_DECREASE,
    STOCK_COUNT_ADJUSTMENT
}