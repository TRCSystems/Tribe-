package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmPostDraftRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A seller's WhatsApp photos and description, turned into a Page post. */
class SmmPostUploadServiceTest {

    static final String SELLER = "254712000111";

    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    SmmAccountRepository accounts = mock(SmmAccountRepository.class);
    SmmPostDraftRepository drafts = mock(SmmPostDraftRepository.class);
    WhatsAppCloudClient whatsapp = mock(WhatsAppCloudClient.class);
    MetaGraphClient graph = mock(MetaGraphClient.class);
    ShoppingListReader claude = mock(ShoppingListReader.class);
    SmmCaptionIndexService captionIndex = mock(SmmCaptionIndexService.class);
    SmmPostUploadService service = new SmmPostUploadService(profiles, accounts, drafts, whatsapp, graph, claude, captionIndex);

    SmmSellerProfile seller = SmmSellerProfile.builder().id(1L).merchantId(23L).whatsappNumber("0712 000 111").build();
    SmmAccount account = SmmAccount.builder().merchantId(23L).platform(SocialPlatform.FACEBOOK).pageId("PAGE1")
            .status(SmmAccount.Status.ACTIVE).accessToken("TOKEN").build();

    static Map<String, Object> image(String id) {
        return Map.of("image", Map.of("id", id));
    }

    @BeforeEach
    void setUp() {
        when(profiles.findAll()).thenReturn(List.of(seller));
    }

    @Test
    void aSellerWithNoConnectedPageIsNotIntercepted() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of());
        assertFalse(service.handle(SELLER, "image", "", image("MEDIA1")));
        verifyNoInteractions(whatsapp);
    }

    @Test
    void aFirstPhotoStartsCollectingAndSetsAwaiting() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        when(drafts.findFirstByMerchantIdAndStatusOrderByIdDesc(23L, SmmPostDraft.Status.COLLECTING)).thenReturn(Optional.empty());

        assertTrue(service.handle(SELLER, "image", "", image("MEDIA1")));

        ArgumentCaptor<SmmPostDraft> saved = ArgumentCaptor.forClass(SmmPostDraft.class);
        verify(drafts).save(saved.capture());
        assertEquals("MEDIA1", saved.getValue().getMediaIds());
        assertEquals(SmmPostDraft.Status.COLLECTING, saved.getValue().getStatus());
        assertEquals("POST", seller.getAwaiting());
        verify(whatsapp).sendText(eq(SELLER), contains("Got the photo"));
    }

    @Test
    void aSecondPhotoJoinsTheSameDraft() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        seller.setAwaiting("POST");
        seller.setAwaitingSince(LocalDateTime.now());
        SmmPostDraft existing = SmmPostDraft.builder().id(9L).merchantId(23L).status(SmmPostDraft.Status.COLLECTING).mediaIds("MEDIA1").build();
        when(drafts.findFirstByMerchantIdAndStatusOrderByIdDesc(23L, SmmPostDraft.Status.COLLECTING)).thenReturn(Optional.of(existing));

        assertTrue(service.handle(SELLER, "image", "", image("MEDIA2")));

        assertEquals("MEDIA1,MEDIA2", existing.getMediaIds());
        verify(whatsapp).sendText(eq(SELLER), contains("2 photos so far"));
    }

    @Test
    void aDescriptionWhileCollectingPostsASinglePhoto() throws Exception {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        seller.setAwaiting("POST");
        seller.setAwaitingSince(LocalDateTime.now());
        SmmPostDraft draft = SmmPostDraft.builder().id(9L).merchantId(23L).status(SmmPostDraft.Status.COLLECTING).mediaIds("MEDIA1").build();
        when(drafts.findFirstByMerchantIdAndStatusOrderByIdDesc(23L, SmmPostDraft.Status.COLLECTING)).thenReturn(Optional.of(draft));
        when(claude.readText(anyString(), eq("Wrap dress, KES 1500, sizes M and L"), eq(SmmPostUploadService.Caption.class)))
                .thenReturn(new SmmPostUploadService.Caption("Wrap dress, KES 1,500. Available in M and L."));
        when(whatsapp.downloadMediaToFile(eq("MEDIA1"), any(Path.class), anyLong())).thenAnswer(inv -> {
            Path p = inv.getArgument(1);
            java.nio.file.Files.write(p, new byte[]{1, 2, 3});
            return "image/jpeg";
        });
        when(graph.postSinglePhoto(eq("PAGE1"), any(byte[].class), anyString(), eq("Wrap dress, KES 1,500. Available in M and L."), eq("TOKEN")))
                .thenReturn(new MetaGraphClient.PostResult("PAGE1_999", "https://facebook.com/PAGE1/posts/999"));

        service.post(SELLER, seller, account, draft, List.of("MEDIA1"), "Wrap dress, KES 1500, sizes M and L");

        assertEquals(SmmPostDraft.Status.POSTED, draft.getStatus());
        assertEquals("PAGE1_999", draft.getPagePostId());
        verify(captionIndex).indexOnce(23L, "PAGE1_999", "Wrap dress, KES 1,500. Available in M and L.");
        verify(whatsapp).sendButtons(eq(SELLER), contains("https://facebook.com/PAGE1/posts/999"), anyList());
    }

    @Test
    void aDescriptionClearsAwaitingImmediatelyAndHandsOffToTheBackgroundPost() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        seller.setAwaiting("POST");
        seller.setAwaitingSince(LocalDateTime.now());
        SmmPostDraft draft = SmmPostDraft.builder().id(9L).merchantId(23L).status(SmmPostDraft.Status.COLLECTING).mediaIds("MEDIA1").build();
        when(drafts.findFirstByMerchantIdAndStatusOrderByIdDesc(23L, SmmPostDraft.Status.COLLECTING)).thenReturn(Optional.of(draft));

        assertTrue(service.handle(SELLER, "text", "Wrap dress, KES 1500", null));

        assertNull(seller.getAwaiting(), "cleared as soon as the description arrives, before the post finishes");
        assertEquals("Wrap dress, KES 1500", draft.getSellerText());
        verify(whatsapp).sendText(eq(SELLER), contains("Posting it now"));
    }

    @Test
    void aFailedDownloadMarksTheDraftFailedAndTellsTheSeller() {
        when(whatsapp.downloadMediaToFile(eq("MEDIA1"), any(Path.class), anyLong())).thenReturn(null);
        SmmPostDraft draft = SmmPostDraft.builder().id(9L).merchantId(23L).status(SmmPostDraft.Status.COLLECTING).mediaIds("MEDIA1").build();

        service.post(SELLER, seller, account, draft, List.of("MEDIA1"), "anything");

        assertEquals(SmmPostDraft.Status.FAILED, draft.getStatus());
        verify(graph, never()).postSinglePhoto(any(), any(), any(), any(), any());
        verify(whatsapp).sendText(eq(SELLER), contains("didn't go through"));
    }

    @Test
    void takeDownDeletesTheMostRecentPost() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        SmmPostDraft last = SmmPostDraft.builder().merchantId(23L).status(SmmPostDraft.Status.POSTED).pagePostId("PAGE1_999").build();
        when(drafts.findFirstByMerchantIdAndStatusAndPagePostIdIsNotNullOrderByPostedAtDesc(23L, SmmPostDraft.Status.POSTED)).thenReturn(Optional.of(last));

        assertTrue(service.handle(SELLER, "text", "take down", null));

        verify(graph).deletePost("PAGE1_999", "TOKEN");
        verify(whatsapp).sendText(eq(SELLER), contains("Taken down"));
    }

    @Test
    void editReplacesTheCaptionWithTheSellersOwnWords() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        SmmPostDraft last = SmmPostDraft.builder().merchantId(23L).status(SmmPostDraft.Status.POSTED).pagePostId("PAGE1_999").build();
        when(drafts.findFirstByMerchantIdAndStatusAndPagePostIdIsNotNullOrderByPostedAtDesc(23L, SmmPostDraft.Status.POSTED)).thenReturn(Optional.of(last));

        assertTrue(service.handle(SELLER, "text", "EDIT: now KES 1,200", null));

        verify(graph).editPostMessage("PAGE1_999", "now KES 1,200", "TOKEN");
        assertEquals("now KES 1,200", last.getCaption());
    }

    @Test
    void aStaleCollectingSessionIsNotTreatedAsADescription() {
        when(accounts.findByMerchantId(23L)).thenReturn(List.of(account));
        seller.setAwaiting("POST");
        seller.setAwaitingSince(LocalDateTime.now().minusHours(3));

        assertFalse(service.handle(SELLER, "text", "Wrap dress, KES 1500", null));
        verifyNoInteractions(claude);
    }
}
