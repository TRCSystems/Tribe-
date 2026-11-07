package com.dayworks_ltd.loyalty_engine.crm;

import com.dayworks_ltd.loyalty_engine.crm.channels.MessageChannelSender;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmMessage;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;
import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmMessageRepository;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmMessagingService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderLinkService;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Which channel a CRM message goes out on, and the SMS fallback when WhatsApp refuses it. */
class CrmChannelChoiceTest {

    private CrmMessageRepository messages;
    private CrmContactService contacts;
    private ShopOrderLinkService links;
    private MessageChannelSender sms;
    private MessageChannelSender whatsapp;
    private CrmMessagingService service;
    private CrmContact contact;

    @BeforeEach
    void setUp() {
        messages = mock(CrmMessageRepository.class);
        contacts = mock(CrmContactService.class);
        links = mock(ShopOrderLinkService.class);
        MerchantService merchants = mock(MerchantService.class);
        when(merchants.getMerchantById(anyLong())).thenReturn(Optional.empty());
        sms = mock(MessageChannelSender.class);
        whatsapp = mock(MessageChannelSender.class);
        when(sms.channel()).thenReturn(CrmEnums.Channel.SMS);
        when(whatsapp.channel()).thenReturn(CrmEnums.Channel.WHATSAPP);
        when(whatsapp.isEnabled()).thenReturn(true);
        when(sms.deliver(any())).thenReturn(new MessageChannelSender.Result(true, null));
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        when(links.create(anyLong(), anyLong(), any())).thenReturn(new ShopOrderLinkService.NewLink(
                ShopOrderLink.builder().id(5L).build(), "https://tribessystems.co.ke/o/Tok3nAbcde", "Tok3nAbcde"));

        service = new CrmMessagingService(messages, contacts, merchants, List.of(sms, whatsapp), links);
        ReflectionTestUtils.setField(service, "quietStart", "00:00");
        ReflectionTestUtils.setField(service, "quietEnd", "00:00");   // no quiet hours in tests
        ReflectionTestUtils.setField(service, "holdoutPercent", 0);
        ReflectionTestUtils.setField(service, "maxMarketingPerWeek", 3);

        contact = CrmContact.builder().distributorId(23L).shopMerchantId(501L).phone("0712345678")
                .preferredChannel(CrmEnums.Channel.WHATSAPP).marketingOptOut(false).build();
        when(contacts.getOrCreate(23L, 501L)).thenReturn(contact);
    }

    private CrmMessage send(CrmTemplate t) {
        return service.send(23L, 501L, t, Map.of("items", "Tusker", "text", "hi"), "TEST", 1L);
    }

    @Test
    void whatsappShopGetsTemplateWithItsOrderToken() {
        when(whatsapp.deliver(any())).thenReturn(new MessageChannelSender.Result(true, "wamid.1"));
        CrmMessage m = send(CrmTemplate.REORDER_DUE);

        ArgumentCaptor<MessageChannelSender.Outbound> out = ArgumentCaptor.forClass(MessageChannelSender.Outbound.class);
        verify(whatsapp).deliver(out.capture());
        assertEquals("Tok3nAbcde", out.getValue().orderToken());
        assertEquals(CrmTemplate.REORDER_DUE, out.getValue().template());
        assertEquals(CrmEnums.Channel.WHATSAPP, m.getChannel());
        assertEquals("wamid.1", m.getProviderMessageId());
        verify(sms, never()).deliver(any());
    }

    @Test
    void whatsappRefusalFallsBackToSmsWithTheWebLink() {
        when(whatsapp.deliver(any())).thenReturn(new MessageChannelSender.Result(false, null));
        CrmMessage m = send(CrmTemplate.REORDER_DUE);

        ArgumentCaptor<MessageChannelSender.Outbound> out = ArgumentCaptor.forClass(MessageChannelSender.Outbound.class);
        verify(sms).deliver(out.capture());
        assertEquals("0712345678", out.getValue().to());
        assertTrue(out.getValue().body().contains("https://tribessystems.co.ke/o/Tok3nAbcde"));
        assertEquals(CrmEnums.Channel.SMS, m.getChannel());
        assertEquals(CrmEnums.MessageStatus.SENT, m.getStatus());
        assertTrue(m.getSkipReason().contains("sent by SMS"));
    }

    @Test
    void freeTextGoesBySmsOutsideTheWhatsappWindowAndByWhatsappInsideIt() {
        send(CrmTemplate.CUSTOM);
        verify(sms).deliver(any());
        verify(whatsapp, never()).deliver(any());

        contact.setLastWhatsappInboundAt(LocalDateTime.now().minusHours(2));
        when(whatsapp.deliver(any())).thenReturn(new MessageChannelSender.Result(true, "wamid.2"));
        assertEquals(CrmEnums.Channel.WHATSAPP, send(CrmTemplate.CUSTOM).getChannel());
    }

    @Test
    void smsShopsAndSwitchedOffWhatsappUseSms() {
        contact.setPreferredChannel(CrmEnums.Channel.SMS);
        assertEquals(CrmEnums.Channel.SMS, send(CrmTemplate.REORDER_DUE).getChannel());

        contact.setPreferredChannel(CrmEnums.Channel.WHATSAPP);
        when(whatsapp.isEnabled()).thenReturn(false);
        assertEquals(CrmEnums.Channel.SMS, send(CrmTemplate.NEW_STOCK).getChannel());
        verify(whatsapp, never()).deliver(any());
    }

    @Test
    void whatsappInviteAlwaysGoesBySmsWithAWaMeLink() {
        ReflectionTestUtils.setField(service, "whatsappNumber", "254700111222");
        CrmMessage m = send(CrmTemplate.WHATSAPP_INVITE);
        assertEquals(CrmEnums.Channel.SMS, m.getChannel());
        assertTrue(m.getBody().contains("https://wa.me/254700111222?text=Order"), m.getBody());

        ReflectionTestUtils.setField(service, "whatsappNumber", "");
        assertThrows(IllegalArgumentException.class, () -> send(CrmTemplate.WHATSAPP_INVITE));
    }
}
