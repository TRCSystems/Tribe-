package com.dayworks_ltd.loyalty_engine.common;

public enum PaymentMode {
    PREPAID,
    PAY_ON_DELIVERY,
    CONSIGNMENT,
    /**
     * SokoReply order-page orders: the buyer pays the seller's own verified Lipa na M-Pesa till directly, with no
     * STK push from Tribe (most sellers have no Daraja/STK of their own, and Tribe never collects on their behalf).
     * The buyer reports having paid; the seller confirms on their till before fulfilling.
     */
    SELLER_TILL
}
