package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import com.dayworks_ltd.loyalty_engine.smm.intake.SmmIntakeService.Incoming;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient.FetchedComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The last 30 days of comments, fetched after a seller connects. */
class SmmBackfillTest {

    static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    static final Instant SINCE = NOW.minus(SmmBackfillService.WINDOW);
    static final SmmAccount PAGE = SmmAccount.builder().id(1L).merchantId(23L).platform(SocialPlatform.FACEBOOK).pageId("PAGE1").pageName("Clad Hub").build();

    static FetchedComment c(String id, String parent, String from, String text, String time) {
        return new FetchedComment(id, "PAGE1_POST1", parent, from, "Buyer", text, time);
    }

    @Test
    void readsGraphTimes() {
        assertEquals(Instant.parse("2026-11-08T10:15:30Z"), MetaGraphClient.instant("2026-11-08T10:15:30+0000"));
        assertEquals(Instant.parse("2026-11-08T07:15:30Z"), MetaGraphClient.instant("2026-11-08T10:15:30+03:00"));
        assertNull(MetaGraphClient.instant("yesterday"));
        assertNull(MetaGraphClient.instant(null));
    }

    @Test
    void sellersOwnRepliesAreNotStoredButMarkTheCommentAnswered() {
        var p = SmmBackfillService.prepare(PAGE, List.of(
                c("C1", null, "U1", "bei?", "2026-11-01T09:00:00+0000"),
                c("C2", "C1", "PAGE1", "Ni 1,500 dear, DM", "2026-11-01T09:30:00+0000"),
                c("C3", null, "U2", "mko wapi", "2026-11-02T09:00:00+0000")), SINCE);
        assertEquals(List.of("C1", "C3"), p.comments().stream().map(Incoming::commentId).toList());
        assertEquals(Set.of("C1"), p.answeredBySeller());
    }

    @Test
    void dropsOldEmptyAndKeepsRepliesUnderTheirComment() {
        var p = SmmBackfillService.prepare(PAGE, List.of(
                c("OLD", null, "U1", "bei?", "2026-09-01T09:00:00+0000"),
                c("EMPTY", null, "U1", " ", "2026-11-01T09:00:00+0000"),
                c("TOP", "PAGE1_POST1", "U3", "size 40 iko?", "2026-11-03T09:00:00+0000"),
                c("REPLY", "TOP", "U4", "na mimi pia", "2026-11-03T09:05:00+0000")), SINCE);
        List<Incoming> in = p.comments();
        assertEquals(List.of("TOP", "REPLY"), in.stream().map(Incoming::commentId).toList());
        assertNull(in.get(0).parentId(), "a parent that is the post itself means a top-level comment");
        assertEquals("TOP", in.get(1).parentId());
        assertEquals(SocialPlatform.FACEBOOK, in.get(0).platform());
        assertEquals("PAGE1", in.get(0).accountId());
        assertNotNull(in.get(0).createdAt());
    }

    @Test
    void neverMoreThanTheAccountCap() {
        List<FetchedComment> many = new ArrayList<>();
        for (int i = 0; i < SmmBackfillService.MAX_PER_ACCOUNT + 50; i++) many.add(c("C" + i, null, "U" + i, "bei?", "2026-11-05T09:00:00+0000"));
        assertEquals(SmmBackfillService.MAX_PER_ACCOUNT, SmmBackfillService.prepare(PAGE, many, SINCE).comments().size());
    }

    @Test
    void oldBadCommentsAreHiddenButNotAnEmergency() {
        Plan bad = new Plan(null, "pole sana", true, true, null, true);
        SmmComment live = SmmComment.builder().build();
        SmmComment old = SmmComment.builder().backfilled(true).build();
        assertTrue(SmmCommentWorker.urgent(bad, live));
        assertFalse(SmmCommentWorker.urgent(bad, old));
    }

    @Test
    void commentsTheSellerAlreadyAnsweredAreNotAnsweredAgain() {
        Plan buyer = new Plan("Iko! Tumekutumia DM 💚", "Habari! ...", false, true, null);
        Plan p = SmmCommentWorker.alreadyAnswered(buyer);
        assertNull(p.publicReply());
        assertNull(p.privateReply());
        assertFalse(p.handover());

        Plan phone = new Plan("Asante! Tumeficha namba yako", null, true, false, null);
        assertTrue(SmmCommentWorker.alreadyAnswered(phone).hideComment(), "a phone number is still hidden");
    }

    @Test
    void runsOncePerAccountAndRecordsFailures() {
        SmmAccountRepository accounts = mock(SmmAccountRepository.class);
        SmmIntakeService intake = mock(SmmIntakeService.class);
        MetaGraphClient graph = mock(MetaGraphClient.class);
        SmmAccount a = SmmAccount.builder().id(1L).merchantId(23L).platform(SocialPlatform.FACEBOOK).pageId("PAGE1").status(SmmAccount.Status.ACTIVE).build();
        when(accounts.findByStatusAndBackfilledAtIsNull(SmmAccount.Status.ACTIVE)).thenReturn(List.of(a));
        when(graph.facebookPostIdsSince(eq("PAGE1"), any(), anyLong(), anyInt())).thenThrow(new MetaGraphClient.GraphException("Meta said 400: permission"));

        SmmBackfillService s = new SmmBackfillService(accounts, intake, graph);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "enabled", true);
        s.run();

        assertNotNull(a.getBackfilledAt(), "not retried every minute");
        assertTrue(a.getBackfillError().contains("permission"));
        verify(accounts).save(a);
        verifyNoInteractions(intake);
    }
}
