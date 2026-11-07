package com.dayworks_ltd.loyalty_engine.inventory.enums;

public enum CountTrigger {
    MOVEMENT_BASED,     // SKU had an inventory_transaction on the trading date
    RISK_SAMPLED,       // dormant SKU pulled into a random cost-weighted sample
    REALTIME_ALERT,     // high-risk transaction type fired an immediate count requirement
    MANUAL_ADD          // operator manually added a SKU to the queue
}
