package com.dayworks_ltd.loyalty_engine.crm.channels;

import com.dayworks_ltd.loyalty_engine.campaign.service.Campaign;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.utility.Pair;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/** SMS through the existing MobileSasa client. */
@Component
@RequiredArgsConstructor
public class SmsChannelSender implements MessageChannelSender {

    private final Campaign campaign;

    @Override
    public CrmEnums.Channel channel() {
        return CrmEnums.Channel.SMS;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean send(String toNumber, String body) {
        ArrayList<Pair<String, String>> sms = new ArrayList<>();
        sms.add(new Pair<>(toNumber, body));
        return campaign.sendSMSMessage(sms);
    }
}
