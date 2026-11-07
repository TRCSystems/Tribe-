package com.dayworks_ltd.loyalty_engine.crm.channels;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;

import java.util.Map;

/**
 * One way of delivering a CRM message. The CRM only talks to this interface, so switching a shop
 * from SMS to WhatsApp is a configuration change, not a rewrite.
 */
public interface MessageChannelSender {

    CrmEnums.Channel channel();

    /** False until the channel is configured (e.g. WhatsApp before the Meta number is approved). */
    boolean isEnabled();

    /** @return true when the provider accepted the message. */
    boolean send(String toNumber, String body);

    /**
     * A CRM message with everything a richer channel needs: the rendered text (what SMS sends),
     * the template and its values (WhatsApp sends an approved template), and the one-time order
     * token (WhatsApp opens the order form with it).
     */
    record Outbound(String to, String body, CrmTemplate template, Map<String, String> vars, String orderToken) {}

    /** providerMessageId lets delivery receipts find the message later (null when the channel has none). */
    record Result(boolean ok, String providerMessageId) {}

    default Result deliver(Outbound message) {
        return new Result(send(message.to(), message.body()), null);
    }
}
