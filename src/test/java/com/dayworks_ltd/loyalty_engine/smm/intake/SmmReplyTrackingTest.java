package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.intake.SmmMessageTracker.BuyerMessage;
import com.dayworks_ltd.loyalty_engine.smm.intake.SmmMessageTracker.ReplyRate;
import com.dayworks_ltd.loyalty_engine.smm.model.Lead;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmReply;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmLeadRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmReplyRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One private reply per comment: did the buyer answer it? */
class SmmReplyTrackingTest {

    static final long T = 1794132000000L; // ms

    static Map<String, Object> messenger(String object, String accountId, Map<String, Object>... events) {
        return Map.of("object", object, "entry", List.of(Map.of("id", accountId, "time", T, "messaging", List.of(events))));
    }

    static Map<String, Object> msg(String from, String to, Map<String, Object> message) {
        return Map.of("sender", Map.of("id", from), "recipient", Map.of("id", to), "timestamp", T, "message", message);
    }

    @Test
    void readsBuyerMessagesFromMessengerAndInstagram() {
        List<BuyerMessage> fb = SmmMessageTracker.parse(messenger("page", "PAGE1",
                msg("PSID9", "PAGE1", Map.of("mid", "m1", "text", "Black please, niko Rongai"))));
        assertEquals(1, fb.size());
        assertEquals(SocialPlatform.FACEBOOK, fb.get(0).platform());
        assertEquals("PSID9", fb.get(0).senderId());
        assertEquals("Black please, niko Rongai", fb.get(0).text());
        assertNotNull(fb.get(0).at());

        List<BuyerMessage> ig = SmmMessageTracker.parse(messenger("instagram", "IG1",
                msg("IGSID4", "IG1", Map.of("mid", "m2", "attachments", List.of(Map.of("type", "image"))))));
        assertEquals(SocialPlatform.INSTAGRAM, ig.get(0).platform());
        assertEquals("[photo or attachment]", ig.get(0).text());
    }

    @Test
    void skipsTheShopsOwnMessagesReadsAndCommentWebhooks() {
        assertTrue(SmmMessageTracker.parse(messenger("page", "PAGE1",
                msg("PAGE1", "PSID9", Map.of("mid", "m3", "text", "Hi! The dress is KES 3,500", "is_echo", true)))).isEmpty(), "echo");
        assertTrue(SmmMessageTracker.parse(messenger("page", "PAGE1",
                Map.of("sender", Map.of("id", "PSID9"), "recipient", Map.of("id", "PAGE1"), "read", Map.of("watermark", T)))).isEmpty(), "read receipt");
        assertTrue(SmmMessageTracker.parse(Map.of("object", "page", "entry", List.of(Map.of("id", "PAGE1", "changes", List.of())))).isEmpty(), "comments are not messages");
        assertTrue(SmmMessageTracker.parse(Map.of("object", "whatsapp_business_account")).isEmpty());
    }

    @Test
    void firstAnswerIsKeptLaterMessagesMoveTheChatWindow() {
        SmmReply r = SmmReply.builder().kind(SmmReply.Kind.PRIVATE).build();
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 8, 10, 0);
        assertTrue(SmmMessageTracker.record(r, "Black", t1));
        assertFalse(SmmMessageTracker.record(r, "Nitalipa jioni", t1.plusMinutes(30)));
        assertEquals(t1, r.getBuyerRepliedAt());
        assertEquals("Black", r.getBuyerReplyText());
        assertEquals(t1.plusMinutes(30), r.getLastBuyerMessageAt());
        assertEquals(2, r.getBuyerMessageCount());
    }

    @Test
    void marksTheMatchingPrivateReplyAndWarmsTheLead() {
        SmmAccountRepository accounts = mock(SmmAccountRepository.class);
        SmmReplyRepository replies = mock(SmmReplyRepository.class);
        SmmLeadRepository leads = mock(SmmLeadRepository.class);
        when(accounts.findByPlatformAndPageId(SocialPlatform.FACEBOOK, "PAGE1"))
                .thenReturn(Optional.of(SmmAccount.builder().merchantId(23L).build()));
        SmmReply sent = SmmReply.builder().id(5L).merchantId(23L).kind(SmmReply.Kind.PRIVATE).status(SmmReply.Status.SENT).recipientId("PSID9").build();
        when(replies.findFirstByMerchantIdAndKindAndStatusAndRecipientIdAndSentAtAfterOrderBySentAtDesc(
                eq(23L), eq(SmmReply.Kind.PRIVATE), eq(SmmReply.Status.SENT), eq("PSID9"), any())).thenReturn(Optional.of(sent));
        Lead lead = Lead.builder().merchantId(23L).senderPsid("PSID9").platform(Lead.Platform.FACEBOOK).temperature(Lead.Temperature.WARM).messageCount(1).build();
        when(leads.findFirstByMerchantIdAndSenderPsidAndPlatform(23L, "PSID9", Lead.Platform.FACEBOOK)).thenReturn(Optional.of(lead));

        SmmMessageTracker tracker = new SmmMessageTracker(accounts, replies, leads);
        Map<String, Object> payload = messenger("page", "PAGE1", msg("PSID9", "PAGE1", Map.of("mid", "m1", "text", "Black")));
        assertEquals(1, tracker.track(payload));
        assertEquals(0, tracker.track(payload), "a second message is not a second answer");

        assertNotNull(sent.getBuyerRepliedAt());
        assertEquals("Black", sent.getBuyerReplyText());
        assertEquals(Lead.Temperature.HOT, lead.getTemperature());
        verify(replies, times(2)).save(sent);
    }

    @Test
    void messagesFromStrangersOrUnknownPagesChangeNothing() {
        SmmAccountRepository accounts = mock(SmmAccountRepository.class);
        SmmReplyRepository replies = mock(SmmReplyRepository.class);
        SmmLeadRepository leads = mock(SmmLeadRepository.class);
        when(accounts.findByPlatformAndPageId(any(), eq("PAGE1"))).thenReturn(Optional.of(SmmAccount.builder().merchantId(23L).build()));
        when(replies.findFirstByMerchantIdAndKindAndStatusAndRecipientIdAndSentAtAfterOrderBySentAtDesc(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        SmmMessageTracker tracker = new SmmMessageTracker(accounts, replies, leads);

        assertEquals(0, tracker.track(messenger("page", "PAGE1", msg("PSID1", "PAGE1", Map.of("text", "hello")))));
        assertEquals(0, tracker.track(messenger("page", "OTHER", msg("PSID1", "OTHER", Map.of("text", "hello")))));
        verify(replies, never()).save(any());
        verifyNoInteractions(leads);
    }

    @Test
    void replyRateCountsAnsweredOrOrderedOnce() {
        SmmReply answered = SmmReply.builder().commentId(1L).buyerRepliedAt(LocalDateTime.now()).build();
        SmmReply ordered = SmmReply.builder().commentId(2L).build();
        SmmReply both = SmmReply.builder().commentId(3L).buyerRepliedAt(LocalDateTime.now()).build();
        SmmReply silent = SmmReply.builder().commentId(4L).build();

        ReplyRate rate = ReplyRate.of(List.of(answered, ordered, both, silent), Set.of(2L, 3L));
        assertEquals(4, rate.sent());
        assertEquals(2, rate.answered());
        assertEquals(2, rate.ordered());
        assertEquals(3, rate.answeredOrOrdered());
        assertEquals(75.0, rate.rate());

        assertNull(ReplyRate.of(List.of(), Set.of()).rate(), "nothing sent: no rate, not 0%");
    }
}
