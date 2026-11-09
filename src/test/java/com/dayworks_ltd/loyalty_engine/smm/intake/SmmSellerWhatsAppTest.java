package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The seller on WhatsApp: hot-lead alerts, PAUSE / RESUME, the morning summary and its one-tap review. */
class SmmSellerWhatsAppTest {

    static final String SELLER = "254712000111";

    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    SmmAccountRepository accounts = mock(SmmAccountRepository.class);
    SmmLeadRepository leads = mock(SmmLeadRepository.class);
    SmmHandoverRepository handovers = mock(SmmHandoverRepository.class);
    SmmCommentRepository comments = mock(SmmCommentRepository.class);
    SmmReplyRepository replies = mock(SmmReplyRepository.class);
    SmmFaqRepository faqs = mock(SmmFaqRepository.class);
    WhatsAppCloudClient whatsapp = mock(WhatsAppCloudClient.class);
    WhatsAppProperties props = mock(WhatsAppProperties.class);
    MetaGraphClient graph = mock(MetaGraphClient.class);

    SmmSellerCommands commands = new SmmSellerCommands(profiles, accounts, leads, handovers, comments, replies, faqs, whatsapp, graph);
    SmmLeadAlertService leadAlerts = new SmmLeadAlertService(leads, profiles, whatsapp, props);

    SmmSellerProfile seller = SmmSellerProfile.builder().id(1L).merchantId(23L).whatsappNumber("0712 000 111")
            .shadowMode(false).build();
    SmmAccount page = SmmAccount.builder().id(1L).merchantId(23L).platform(SocialPlatform.FACEBOOK).pageName("ASANO GROUP")
            .accessToken("TOKEN").status(SmmAccount.Status.ACTIVE).build();
    LocalDateTime now = LocalDateTime.now(SmmIntakeService.NAIROBI);

    @BeforeEach
    void setUp() {
        when(profiles.findAll()).thenReturn(List.of(seller));
        when(profiles.findById(1L)).thenReturn(Optional.of(seller));
        when(profiles.findByMerchantId(23L)).thenReturn(Optional.of(seller));
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(page));
        when(accounts.findById(1L)).thenReturn(Optional.of(page));
        when(props.canSend()).thenReturn(true);
        when(whatsapp.sendTemplateWithReplies(any(), any(), any(), any())).thenReturn(new WhatsAppCloudClient.Sent(true, "wamid", null));
    }

    // ───────────── pause ─────────────

    @Test
    void pauseAndResumeFromWhatsApp() {
        assertTrue(commands.handle(SELLER, "text", "pause"));
        assertTrue(seller.isAgentsPaused());
        verify(whatsapp).sendButtons(eq(SELLER), contains("ASANO GROUP"), any());

        assertTrue(commands.handle(SELLER, "button", "SMM_RESUME"));
        assertFalse(seller.isAgentsPaused());

        assertTrue(commands.handle(SELLER, "text", "Simamisha"), "Swahili works too");
        assertTrue(seller.isAgentsPaused());
    }

    @Test
    void otherPeopleAndOtherMessagesGoOnToTheUsualRouting() {
        assertFalse(commands.handle("254799999999", "text", "PAUSE"), "not a seller");
        assertFalse(commands.handle(SELLER, "text", "hello"), "a seller just chatting, nothing asked");
        assertFalse(seller.isAgentsPaused());
    }

    // ───────────── hot leads ─────────────

    Lead hot(long id, String name, String phone, String want) {
        return Lead.builder().id(id).merchantId(23L).senderName(name).phoneNumber(phone).hotWant(want)
                .platform(Lead.Platform.FACEBOOK).hotAlertPending(true).lastContactAt(now).build();
    }

    @Test
    void aHotBuyerReachesTheSellerWithTheirNumber() {
        Lead jane = hot(5L, "Jane Wanjiru", "0712345678", "black, size 12");
        when(leads.findByMerchantIdAndHotAlertPendingTrueOrderByLastContactAtAsc(23L)).thenReturn(List.of(jane));

        leadAlerts.maybeAlert(23L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> params = ArgumentCaptor.forClass(List.class);
        verify(whatsapp).sendTemplateWithReplies(eq("0712 000 111"), eq(SmmLeadAlertService.TEMPLATE), params.capture(),
                eq(List.of("SMM_CALLED:5", "SMM_PAUSE")));
        assertEquals("Facebook", params.getValue().get(0));
        assertEquals("Jane Wanjiru", params.getValue().get(1));
        assertEquals("black, size 12", params.getValue().get(2));
        assertTrue(params.getValue().get(3).startsWith("0712345678"));
        assertFalse(jane.getHotAlertPending());
        assertNotNull(jane.getHotAlertedAt());
    }

    @Test
    void severalHotBuyersInAFewMinutesGoOutTogether() {
        List<Lead> three = List.of(hot(1L, "Ann", null, "red"), hot(2L, "Bea", "0722000000", "to buy"), hot(3L, "Cate", null, "size 10"));
        assertEquals("Cate and 2 more", SmmLeadAlertService.params(three).get(1));
        assertTrue(SmmLeadAlertService.params(three).get(3).contains("0722000000"));
        assertEquals("SMM_CALLED:1,2,3", SmmLeadAlertService.calledPayload(three));

        when(leads.existsByMerchantIdAndHotAlertedAtAfter(eq(23L), any())).thenReturn(true);
        leadAlerts.maybeAlert(23L);
        verifyNoInteractions(whatsapp); // within 15 minutes of the last alert: the sweep sends it later
    }

    @Test
    void calledMarksTheBuyersContacted() {
        Lead jane = hot(5L, "Jane", null, "red");
        when(leads.findById(5L)).thenReturn(Optional.of(jane));

        assertTrue(commands.handle(SELLER, "button", "SMM_CALLED:5"));

        assertEquals(Lead.LeadStatus.CONTACTED, jane.getStatus());
    }

    // ───────────── questions: the seller's answer becomes memory ─────────────

    SmmComment question = SmmComment.builder().id(9L).merchantId(23L).accountId(1L).platform(SocialPlatform.FACEBOOK)
            .text("Mnadeliver Kisumu?").language("sw").negative(false).build();
    SmmHandover handover = SmmHandover.builder().id(4L).merchantId(23L).commentId(9L).reason(SmmHandover.Reason.UNSURE).build();

    @Test
    void theSellersAnswerIsSavedAndSentToTheBuyerWhileTheChatIsOpen() {
        when(handovers.findByMerchantIdAndUrgentFalseAndAnsweredAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(eq(23L), any()))
                .thenReturn(List.of(handover), List.of());
        when(comments.findById(9L)).thenReturn(Optional.of(question));
        when(handovers.findById(4L)).thenReturn(Optional.of(handover));
        SmmReply dm = SmmReply.builder().id(7L).commentId(9L).kind(SmmReply.Kind.PRIVATE).status(SmmReply.Status.SENT)
                .recipientId("PSID9").lastBuyerMessageAt(now.minusHours(2)).build();
        when(replies.findByCommentIdOrderByCreatedAtAsc(9L)).thenReturn(List.of(dm));

        assertTrue(commands.handle(SELLER, "button", "SMM_QUESTIONS"));
        assertEquals("FAQ:4", seller.getAwaiting());
        verify(whatsapp).sendButtons(eq(SELLER), contains("Mnadeliver Kisumu?"), any());

        assertTrue(commands.handle(SELLER, "text", "Yes, KES 300 by G4S, arrives next day"));

        ArgumentCaptor<SmmFaq> faq = ArgumentCaptor.forClass(SmmFaq.class);
        verify(faqs).save(faq.capture());
        assertEquals("Mnadeliver Kisumu?", faq.getValue().getQuestion());
        assertEquals("Yes, KES 300 by G4S, arrives next day", faq.getValue().getAnswer());
        assertNotNull(handover.getAnsweredAt());
        verify(graph).sendMessage("PSID9", "Yes, KES 300 by G4S, arrives next day", "TOKEN");
        assertNull(seller.getAwaiting());
    }

    @Test
    void noMessageToABuyerWhoNeverWroteBack() {
        when(handovers.findById(4L)).thenReturn(Optional.of(handover));
        when(comments.findById(9L)).thenReturn(Optional.of(question));
        when(replies.findByCommentIdOrderByCreatedAtAsc(9L)).thenReturn(List.of(SmmReply.builder().kind(SmmReply.Kind.PRIVATE)
                .status(SmmReply.Status.SENT).recipientId("PSID9").build()));
        seller.setAwaiting("FAQ:4");
        seller.setAwaitingSince(now);

        commands.handle(SELLER, "text", "Yes we deliver");

        verify(graph, never()).sendMessage(any(), any(), any());
        verify(whatsapp).sendText(eq(SELLER), contains("Page inbox"));
    }

    // ───────────── reply check: a fix teaches the voice ─────────────

    @Test
    void aFixJoinsTheSellersVoiceSamples() {
        SmmReply reply = SmmReply.builder().id(11L).merchantId(23L).commentId(9L).kind(SmmReply.Kind.PUBLIC)
                .text("Thanks! We've sent you a DM 💚").status(SmmReply.Status.SENT).build();
        when(replies.findById(11L)).thenReturn(Optional.of(reply));

        assertTrue(commands.handle(SELLER, "button", "SMM_FIX:11"));
        assertEquals("FIX:11", seller.getAwaiting());
        assertTrue(commands.handle(SELLER, "text", "Iko dear 😍 check inbox"));

        assertEquals("Iko dear 😍 check inbox", reply.getSellerCorrection());
        assertNotNull(reply.getSellerReviewedAt());
        assertTrue(seller.getVoiceSamples().endsWith("Iko dear 😍 check inbox"));
    }

    @Test
    void voiceSamplesKeepTheNewestThirty() {
        String samples = null;
        for (int i = 1; i <= 35; i++) samples = SmmSellerCommands.addSample(samples, "line " + i + "\nsecond part");
        String[] lines = samples.split("\n");
        assertEquals(30, lines.length);
        assertEquals("line 6 second part", lines[0]);
        assertEquals("line 35 second part", lines[29]);
    }

    // ───────────── morning summary ─────────────

    @Test
    void theMorningSummaryCarriesYesterdayAndTheThreeButtons() {
        SmmMorningSummary summary = new SmmMorningSummary(profiles, accounts, comments, replies, leads, handovers, whatsapp, props);
        when(comments.countByMerchantIdAndReceivedAtAfter(eq(23L), any())).thenReturn(42L);
        when(comments.countByMerchantIdAndBuyerTrueAndReceivedAtAfter(eq(23L), any())).thenReturn(30L);

        assertTrue(summary.send(seller));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> params = ArgumentCaptor.forClass(List.class);
        verify(whatsapp).sendTemplateWithReplies(eq("0712 000 111"), eq(SmmMorningSummary.TEMPLATE), params.capture(),
                eq(List.of("SMM_QUESTIONS", "SMM_REPLIES", "SMM_PAUSE")));
        assertEquals("ASANO GROUP", params.getValue().get(0));
        assertEquals("42", params.getValue().get(1));
        assertEquals("30", params.getValue().get(2));
        assertEquals(8, params.getValue().size());
    }

    @Test
    void aQuietDayGetsNoSummary() {
        SmmMorningSummary summary = new SmmMorningSummary(profiles, accounts, comments, replies, leads, handovers, whatsapp, props);
        assertFalse(summary.send(seller));
        verify(whatsapp, never()).sendTemplateWithReplies(any(), any(), any(), any());
    }

    @Test
    void templateTextIsOneLine() {
        assertEquals("a b c", WhatsAppCloudClient.templateText("a\n\nb\t   c"));
        assertEquals("-", WhatsAppCloudClient.templateText("  "));
    }
}
