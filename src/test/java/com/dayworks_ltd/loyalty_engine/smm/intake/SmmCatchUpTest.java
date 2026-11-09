package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.intake.SmmIntakeService.Incoming;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient.FetchedComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmComment;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCommentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Comments Meta never sent us are picked up and treated as live. */
class SmmCatchUpTest {

    static final Instant NOW = Instant.parse("2026-11-09T17:00:00Z");

    SmmAccountRepository accounts = mock(SmmAccountRepository.class);
    SmmIntakeService intake = mock(SmmIntakeService.class);
    MetaGraphClient graph = mock(MetaGraphClient.class);
    SmmCatchUpService catchUp = new SmmCatchUpService(accounts, intake, graph);

    SmmAccount page = SmmAccount.builder().id(1L).merchantId(23L).platform(SocialPlatform.FACEBOOK).pageId("PAGE1")
            .pageName("ASANO GROUP").accessToken("TOKEN").status(SmmAccount.Status.ACTIVE)
            .backfilledAt(LocalDateTime.of(2026, 11, 9, 15, 22)).build();

    static FetchedComment c(String id, String from, String text, Instant at) {
        return new FetchedComment(id, "PAGE1_POST1", null, from, "Jacob", text, at.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentCommentsWeDontHaveAreQueuedAsLive() {
        when(graph.facebookPostIdsSince(eq("PAGE1"), eq("TOKEN"), anyLong(), anyInt())).thenReturn(List.of("PAGE1_POST1"));
        when(graph.facebookComments(eq("PAGE1_POST1"), eq("TOKEN"), anyInt())).thenReturn(List.of(
                c("C1", "U1", "You are a conman you don't answer messages inbox", NOW.minusSeconds(4 * 3600)),
                c("C2", "U2", "Where are you located", NOW.minusSeconds(3 * 86400)), // older: covered by the backfill
                new FetchedComment("C3", "PAGE1_POST1", "C1", "PAGE1", "ASANO GROUP", "Pole sana", NOW.toString())));
        when(intake.storeCaughtUp(any(), any())).thenReturn(1);

        assertEquals(1, catchUp.catchUp(page, NOW));

        ArgumentCaptor<List<Incoming>> stored = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Set<String>> answered = ArgumentCaptor.forClass(Set.class);
        verify(intake).storeCaughtUp(stored.capture(), answered.capture());
        assertEquals(List.of("C1"), stored.getValue().stream().map(Incoming::commentId).toList());
        assertEquals(Set.of("C1"), answered.getValue(), "the seller replied to C1, so it isn't answered again");
    }

    @Test
    void accountsStillWaitingForTheBackfillAreSkipped() {
        page.setBackfilledAt(null);
        when(accounts.findByStatus(SmmAccount.Status.ACTIVE)).thenReturn(List.of(page));

        catchUp.run();

        verifyNoInteractions(graph, intake);
    }

    @Test
    void aMetaErrorOnOneAccountDoesNotStopTheOthers() {
        SmmAccount other = SmmAccount.builder().id(2L).merchantId(24L).platform(SocialPlatform.FACEBOOK).pageId("PAGE2")
                .accessToken("TOKEN2").status(SmmAccount.Status.ACTIVE).backfilledAt(LocalDateTime.now()).build();
        when(accounts.findByStatus(SmmAccount.Status.ACTIVE)).thenReturn(List.of(page, other));
        when(graph.facebookPostIdsSince(eq("PAGE1"), any(), anyLong(), anyInt())).thenThrow(new MetaGraphClient.GraphException("Meta said 400"));
        when(graph.facebookPostIdsSince(eq("PAGE2"), any(), anyLong(), anyInt())).thenReturn(List.of());

        catchUp.run();

        verify(graph).facebookPostIdsSince(eq("PAGE2"), any(), anyLong(), anyInt());
    }

    @Test
    void caughtUpCommentsAreStoredAsLiveNotAsBackfill() {
        SmmAccountRepository accountRepo = mock(SmmAccountRepository.class);
        SmmCommentRepository commentRepo = mock(SmmCommentRepository.class);
        when(accountRepo.findByPlatformAndPageId(SocialPlatform.FACEBOOK, "PAGE1")).thenReturn(Optional.of(page));
        SmmIntakeService real = new SmmIntakeService(accountRepo, commentRepo);
        Incoming in = new Incoming(SocialPlatform.FACEBOOK, "PAGE1", "C1", "PAGE1_POST1", null, "U1", "Jacob",
                "You are a conman", LocalDateTime.of(2026, 11, 9, 15, 45));

        assertEquals(1, real.storeCaughtUp(List.of(in), Set.of()));

        ArgumentCaptor<SmmComment> saved = ArgumentCaptor.forClass(SmmComment.class);
        verify(commentRepo).save(saved.capture());
        assertFalse(saved.getValue().getBackfilled(), "live: answered, and hidden with an alert if bad");
        assertNull(page.getLastWebhookAt(), "a catch-up is not a webhook");
    }
}
