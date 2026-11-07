package com.dayworks_ltd.loyalty_engine.orders.models;

/** Who placed an order. Used by the CRM to measure self-ordering. */
public enum OrderSource {
    /** Placed with the distributor's own login (e.g. SFA app on a shared account). */
    DISTRIBUTOR,
    /** Placed by a FIELD_REP login (SFA app). */
    REP,
    /** Placed by the shop itself (from a CRM link). */
    SHOP,
    /** Placed by a consumer through the WhatsApp shop assistant (buyer is the store's "WhatsApp customers" account). */
    CUSTOMER
}
