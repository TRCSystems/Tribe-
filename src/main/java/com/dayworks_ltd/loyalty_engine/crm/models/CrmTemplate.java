package com.dayworks_ltd.loyalty_engine.crm.models;

import java.util.Map;

/**
 * Fixed message library (also the set that will be submitted to Meta for WhatsApp approval).
 * Marketing copy: English with Kenyan Swahili/Sheng openers, short enough for one SMS where possible.
 * Placeholders: {shop} {distributor} {items} {link} {count} {text} {walink}.
 * {link} is filled automatically with a one-time ordering link for the shop.
 * Marketing templates count towards frequency caps, respect opt-out and quiet hours, and carry the
 * STOP footer; utility templates (order updates) do not.
 */
public enum CrmTemplate {

    WEEKEND_STOCKUP(true,
            "{distributor}: Hey {shop}, weekend imefika! Stock up early - your usual {items}. Order here: {link}"),
    PAYDAY_WEEK(true,
            "{distributor}: Hey {shop}, mwisho wa mwezi - customers wako na pesa! Top up {items} now: {link}"),
    REORDER_DUE(true,
            "{distributor}: Sasa {shop}! Shelf iko low? Restock your usual {items} in 2 taps: {link}"),
    NEW_STOCK(true,
            "{distributor}: Mzigo imefika! Fresh stock of {items} just landed. Order yours before it runs out: {link}"),
    WIN_BACK(true,
            "{distributor}: {shop}, tumekumiss! Customers are asking for {items}. Order today: {link}"),
    SELF_ORDER_INVITE(true,
            "{distributor}: Hey {shop}, sasa unaweza order yourself anytime - no need to wait for the rep. Order here: {link}"),
    /** SMS only: moves a shop onto WhatsApp ordering. {walink} opens a chat with the Tribe number. */
    WHATSAPP_INVITE(true,
            "{distributor}: Hey {shop}, sasa unaweza order on WhatsApp! Tap {walink} and send the message - we reply with your order form."),
    PROMO(true,
            "{distributor}: Hey {shop}, {text}. Order here: {link}"),
    ORDER_CONFIRMED(false,
            "{distributor}: Hi {shop}, your order {count} is confirmed. We'll tell you when it's on the way."),
    ORDER_DISPATCHED(false,
            "{distributor}: Hi {shop}, your order {count} is on the way."),
    ORDER_DELIVERED(false,
            "{distributor}: Hi {shop}, your order {count} has been delivered. Asante for ordering with us!"),
    /** Free text from the distributor/rep to one shop (marketing rules apply). */
    CUSTOM(true, "{distributor}: {text}");

    public static final String STOP_FOOTER = " Reply STOP to opt out.";

    private final boolean marketing;
    private final String text;

    CrmTemplate(boolean marketing, String text) {
        this.marketing = marketing;
        this.text = text;
    }

    public boolean isMarketing() {
        return marketing;
    }

    /** True when the message carries an ordering link. */
    public boolean needsLink() {
        return text.contains("{link}");
    }

    public String render(Map<String, String> vars) {
        String out = text;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        out = out.replaceAll("\\{[a-z]+}", "").replaceAll(" {2,}", " ").replace(" .", ".").trim();
        return marketing ? out + STOP_FOOTER : out;
    }
}
