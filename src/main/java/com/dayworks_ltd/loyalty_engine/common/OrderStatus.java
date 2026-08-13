package com.dayworks_ltd.loyalty_engine.common;

public enum OrderStatus {
    PENDING,          // PAY_ON_DELIVERY order created, awaiting dispatch
    PENDING_PAYMENT,  // PREPAID order created, STK push sent
    PAID,             // Payment confirmed via M-Pesa callback
    FULFILLED,        // Distributor has packed and dispatched
    RECEIVED,         // Merchant confirmed receipt
    CANCELLED,
    PAYMENT_FAILED,    // STK push failed at creation — needs retry
    INVOICED,
    PARTIAL_PAYMENT,
    OVERDUE,
    AT_RISK// NEW — past dueDate, unpaid

}