package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCommentRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmReplyRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.util.MultiValueMap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phase 3: the queued replies go out to Facebook and Instagram. */
class SmmReplySenderTest {

    SmmReplyRepository replies = mock(SmmReplyRepository.class);
    SmmCommentRepository comments = mock(SmmCommentRepository.class);
    SmmAccountRepository accounts = mock(SmmAccountRepository.class);
    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    MetaGraphClient graph = mock(MetaGraphClient.class);
    SmmReplySender sender = new SmmReplySender(replies, comments, accounts, profiles, graph);

    SmmComment comment = SmmComment.builder().id(7L).merchantId(23L).accountId(1L).platform(SocialPlatform.FACEBOOK)
            .commentId("POST1_C1").commenterId(null).text("Where are you located")
            .commentCreatedAt(LocalDateTime.now(SmmIntakeService.NAIROBI).minusMinutes(1)).build();
    SmmAccount page = SmmAccount.builder().id(1L).merchantId(23L).platform(SocialPlatform.FACEBOOK).pageId("PAGE1")
            .accessToken("PAGE_TOKEN").status(SmmAccount.Status.ACTIVE).build();
    SmmSellerProfile live = SmmSellerProfile.builder().merchantId(23L).shadowMode(false).agentsPaused(false).build();

    @BeforeEach
    void setUp() {
        when(comments.findById(7L)).thenReturn(Optional.of(comment));
        when(accounts.findById(1L)).thenReturn(Optional.of(page));
        when(profiles.findByMerchantId(23L)).thenReturn(Optional.of(live));
    }

    static SmmReply reply(SmmReply.Kind kind, String text) {
        return SmmReply.builder().id(kind == SmmReply.Kind.PUBLIC ? 1L : 2L).merchantId(23L).commentId(7L).kind(kind)
                .text(text).status(SmmReply.Status.PENDING).build();
    }

    @Test
    void sendsThePublicReplyThenThePrivateOne() {
        SmmReply pub = reply(SmmReply.Kind.PUBLIC, "We're at NAIROBI CBD 📍 We've sent you a DM 💚");
        SmmReply dm = reply(SmmReply.Kind.PRIVATE, "Hi! 💚 We're at NAIROBI CBD, TOM MBOYA STREET.");
        when(replies.findTop50ByStatusOrderByIdAsc(SmmReply.Status.PENDING)).thenReturn(List.of(pub, dm));
        when(graph.replyToComment(SocialPlatform.FACEBOOK, "POST1_C1", pub.getText(), "PAGE_TOKEN")).thenReturn("POST1_C2");
        when(graph.privateReply("POST1_C1", dm.getText(), "PAGE_TOKEN")).thenReturn(new MetaGraphClient.SentMessage("PSID9", "m_1"));

        sender.run();

        var order = inOrder(graph);
        order.verify(graph).replyToComment(SocialPlatform.FACEBOOK, "POST1_C1", pub.getText(), "PAGE_TOKEN");
        order.verify(graph).privateReply("POST1_C1", dm.getText(), "PAGE_TOKEN");
        assertEquals(SmmReply.Status.SENT, pub.getStatus());
        assertEquals("POST1_C2", pub.getMetaMessageId());
        assertNotNull(pub.getSentAt());
        assertEquals(SmmReply.Status.SENT, dm.getStatus());
        assertEquals("PSID9", dm.getRecipientId(), "the buyer's answers arrive with this ID");
        verify(replies).save(pub);
        verify(replies).save(dm);
    }

    @Test
    void aMetaErrorIsRecordedAndNotRetried() {
        SmmReply pub = reply(SmmReply.Kind.PUBLIC, "Hi");
        when(replies.findTop50ByStatusOrderByIdAsc(SmmReply.Status.PENDING)).thenReturn(List.of(pub));
        when(graph.replyToComment(any(), any(), any(), any()))
                .thenThrow(new MetaGraphClient.GraphException("Meta said 400: access_token=SECRET (#200) Permissions error"));

        sender.run();

        assertEquals(SmmReply.Status.FAILED, pub.getStatus());
        assertTrue(pub.getError().contains("Permissions error"));
        assertFalse(pub.getError().contains("SECRET"));
    }

    @Test
    void pausedOrShadowSellersAreNotSentTo() {
        live.setAgentsPaused(true);
        SmmReply pub = reply(SmmReply.Kind.PUBLIC, "Hi");
        when(replies.findTop50ByStatusOrderByIdAsc(SmmReply.Status.PENDING)).thenReturn(List.of(pub));

        sender.run();

        assertEquals(SmmReply.Status.LOGGED, pub.getStatus());
        verifyNoInteractions(graph);
    }

    @Test
    void noPrivateReplyAfterSevenDays() {
        comment.setCommentCreatedAt(LocalDateTime.now(SmmIntakeService.NAIROBI).minusDays(8));
        SmmReply dm = reply(SmmReply.Kind.PRIVATE, "Hi");
        when(replies.findTop50ByStatusOrderByIdAsc(SmmReply.Status.PENDING)).thenReturn(List.of(dm));

        sender.run();

        assertEquals(SmmReply.Status.FAILED, dm.getStatus());
        verify(graph, never()).privateReply(any(), any(), any());
    }

    @Test
    void aDisconnectedPageFails() {
        page.setStatus(SmmAccount.Status.DISCONNECTED);
        SmmReply pub = reply(SmmReply.Kind.PUBLIC, "Hi");
        when(replies.findTop50ByStatusOrderByIdAsc(SmmReply.Status.PENDING)).thenReturn(List.of(pub));

        sender.run();

        assertEquals(SmmReply.Status.FAILED, pub.getStatus());
        verifyNoInteractions(graph);
    }

    @Test
    void onlyLiveCommentsFromLiveSellersAreQueued() {
        SmmSellerProfile shadow = SmmSellerProfile.builder().merchantId(23L).build(); // new sellers start in shadow mode
        assertFalse(SmmCommentWorker.sendable(shadow, comment));
        assertTrue(SmmCommentWorker.sendable(live, comment));
        comment.setBackfilled(true);
        assertFalse(SmmCommentWorker.sendable(live, comment), "old comments fetched at connect are not answered weeks later");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePrivateReplyIsAddressedByCommentAndTheTokenStaysOutOfTheUrl() {
        Map<String, String> fields = MetaGraphClient.privateReplyFields("POST1_C1", "Bei ni 1,500 + delivery 💚");
        assertEquals("{\"comment_id\":\"POST1_C1\"}", fields.get("recipient"));
        assertEquals("{\"text\":\"Bei ni 1,500 + delivery 💚\"}", fields.get("message"));

        HttpEntity<MultiValueMap<String, String>> body = MetaGraphClient.formBody(Map.of("message", "Hi"), "PAGE_TOKEN");
        assertEquals("PAGE_TOKEN", body.getBody().getFirst("access_token"));
        assertEquals("application/x-www-form-urlencoded", body.getHeaders().getContentType().toString());
    }
}
