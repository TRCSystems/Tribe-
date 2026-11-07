package com.dayworks_ltd.loyalty_engine.crm.channels;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WhatsApp through the Meta Cloud API on the Tribe number. CRM templates go out as their approved
 * WhatsApp template (with an "Order now" button that opens the order form); anything without a
 * template is sent as plain text, which WhatsApp only allows within 24h of the shop's last message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppChannelSender implements MessageChannelSender {

    private final WhatsAppProperties props;
    private final WhatsAppCloudClient client;

    @Override
    public CrmEnums.Channel channel() {
        return CrmEnums.Channel.WHATSAPP;
    }

    @Override
    public boolean isEnabled() {
        return props.canSend();
    }

    @Override
    public boolean send(String toNumber, String body) {
        return client.sendText(toNumber, body).ok();
    }

    @Override
    public Result deliver(Outbound message) {
        WhatsAppCloudClient.Sent sent = WhatsAppTemplates.forTemplate(message.template())
                .map(t -> client.sendTemplate(message.to(), t.name(), t.params(message.vars()),
                        t.orderButton() ? message.orderToken() : null))
                .orElseGet(() -> client.sendText(message.to(), message.body()));
        if (!sent.ok()) log.warn("WhatsApp {} to {} not accepted: {}", message.template(), message.to(), sent.error());
        return new Result(sent.ok(), sent.messageId());
    }
}
