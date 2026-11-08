package com.dayworks_ltd.loyalty_engine.merchants;

/**
 * What a merchant has bought. Decides which parts of Tribe they see in the portal and may call.
 * <ul>
 *   <li>{@link #FULL}: the shop system (POS, orders, inventory, customers, reports, finance) plus SokoReply.
 *   Every merchant created before plans existed is FULL.</li>
 *   <li>{@link #SOKOREPLY}: online sellers who only bought SokoReply, the sales agents in their Reel comments.</li>
 * </ul>
 */
public enum MerchantPlan {
    FULL,
    SOKOREPLY;

    /** Lenient parse for requests: blank or unknown means "not given" (null). */
    public static MerchantPlan parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A merchant without a plan set is FULL. */
    public static MerchantPlan of(Merchant merchant) {
        return merchant == null || merchant.getPlan() == null ? FULL : merchant.getPlan();
    }
}
