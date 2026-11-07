package com.dayworks_ltd.loyalty_engine.inventory.enums;



public enum StockCountStatus {
    PENDING,            // queued, not yet counted
    RECOUNT_REQUESTED,  // first blind count did not match; counter asked to recount (still blind)
    COUNTED,
    RECONCILED,         // final count matches the system quantity
    VARIANCE_OPEN,      // final count differs from the system quantity — flagged for the owner
    CLASSIFIED,         // owner has recorded a reason for the variance
    ESCALATED,
    NOT_COUNTED         // day was finalized before this item was counted
}
