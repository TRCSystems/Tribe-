package com.dayworks_ltd.loyalty_engine.smm.order;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmOrderLink;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmOrderLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One public order link per Reel: created once, shared by every commenter, never single-use. */
class SmmOrderLinkServiceTest {

    SmmOrderLinkRepository repository = mock(SmmOrderLinkRepository.class);
    SmmOrderLinkService service = new SmmOrderLinkService(repository);

    {
        ReflectionTestUtils.setField(service, "linkBase", "https://tribessystems.co.ke/s/");
        ReflectionTestUtils.setField(service, "linkDays", 120);
    }

    @Test
    void firstCallCreatesALinkAndLaterCallsReuseIt() {
        when(repository.findByMerchantIdAndPostId(23L, "POST1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            SmmOrderLink l = inv.getArgument(0);
            l.setId(1L);
            return l;
        });

        SmmOrderLinkService.Link first = service.getOrCreate(23L, "POST1");
        assertNotNull(first.token());
        assertTrue(first.url().startsWith("https://tribessystems.co.ke/s/"));
        assertTrue(first.url().endsWith(first.token()));

        SmmOrderLink saved = SmmOrderLink.builder().id(1L).token(first.token()).merchantId(23L).postId("POST1")
                .expiresAt(LocalDateTime.now().plusDays(120)).build();
        when(repository.findByMerchantIdAndPostId(23L, "POST1")).thenReturn(Optional.of(saved));

        SmmOrderLinkService.Link second = service.getOrCreate(23L, "POST1");
        assertEquals(first.token(), second.token(), "the same post always gets the same link");
        verify(repository, times(1)).save(any());
    }

    @Test
    void differentPostsGetDifferentLinks() {
        when(repository.findByMerchantIdAndPostId(eq(23L), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            SmmOrderLink l = inv.getArgument(0);
            l.setId((long) (Math.random() * 1000));
            return l;
        });
        SmmOrderLinkService.Link a = service.getOrCreate(23L, "POST_A");
        SmmOrderLinkService.Link b = service.getOrCreate(23L, "POST_B");
        assertNotEquals(a.token(), b.token());
    }

    @Test
    void anExpiredOrUnknownTokenIsNotFound() {
        when(repository.findByToken("GONE1234")).thenReturn(Optional.of(
                SmmOrderLink.builder().id(1L).token("GONE1234").merchantId(23L).postId("P")
                        .expiresAt(LocalDateTime.now().minusDays(1)).build()));
        assertTrue(service.find("GONE1234").isEmpty(), "expired");
        assertTrue(service.find(null).isEmpty());
        assertTrue(service.find("has spaces!").isEmpty(), "not a plausible token");
    }

    @Test
    void opensAndOrdersAreCounted() {
        SmmOrderLink link = SmmOrderLink.builder().id(1L).token("ABCD1234").merchantId(23L).postId("P")
                .expiresAt(LocalDateTime.now().plusDays(1)).build();
        service.recordOpen(link);
        service.recordOpen(link);
        service.recordOrder(link);
        assertEquals(2, link.getOpenCount());
        assertEquals(1, link.getOrderCount());
        verify(repository, times(3)).save(link);
    }
}
