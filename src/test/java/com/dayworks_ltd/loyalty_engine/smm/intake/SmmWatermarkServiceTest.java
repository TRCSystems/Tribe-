package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The till watermark: who gets it, the TILL command, the words on the video and where they sit. */
class SmmWatermarkServiceTest {

    static final String SELLER = "254712000111";

    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    WhatsAppCloudClient whatsapp = mock(WhatsAppCloudClient.class);
    DarajaB2bClient daraja = mock(DarajaB2bClient.class);
    SmmTillCheckService tillCheck = new SmmTillCheckService(profiles, whatsapp, daraja, true);
    SmmWatermarkService service = new SmmWatermarkService(profiles, whatsapp, tillCheck, "ffmpeg-not-installed", "/fonts/DejaVuSans-Bold.ttf", 600, true);

    SmmSellerProfile seller = SmmSellerProfile.builder().id(1L).merchantId(23L).whatsappNumber("0712 000 111").build();

    @BeforeEach
    void setUp() {
        when(profiles.findAll()).thenReturn(List.of(seller));
        when(profiles.findByMerchantId(23L)).thenReturn(Optional.of(seller));
    }

    static Map<String, Object> document(String mime, String filename) {
        return Map.of("type", "document", "document", Map.of("id", "MEDIA1", "mime_type", mime, "filename", filename));
    }

    @Test
    void aSellerWithoutATillIsToldWhatItIsForAndNoVideoIsMade() {
        assertTrue(service.handle(SELLER, "document", "", document("video/mp4", "reel.mp4")));
        verify(whatsapp).sendText(eq(SELLER), contains("TILL 123456"));
        verify(whatsapp, never()).downloadMediaToFile(any(), any(), anyLong());
    }

    @Test
    void aTillThatIsNotVerifiedYetGetsNoWatermark() {
        seller.setTillNumber("123456");
        assertTrue(service.handle(SELLER, "video", "", Map.of("video", Map.of("id", "MEDIA1", "mime_type", "video/mp4"))));
        verify(whatsapp).sendText(eq(SELLER), contains("still checking your till"));
    }

    @Test
    void theTillCommandTakesATillButNotAPhoneNumber() {
        assertTrue(service.handle(SELLER, "text", "Till 123 456", null));
        assertEquals("123456", seller.getTillNumber());
        assertNull(seller.getTillVerifiedAt(), "only the code of our payment verifies it");
        verify(whatsapp).sendText(eq(SELLER), contains("small payment"));

        assertTrue(service.handle(SELLER, "text", "TILL 0712345678", null));
        assertEquals("123456", seller.getTillNumber(), "a personal M-Pesa number is not a till");
        verify(whatsapp).sendText(eq(SELLER), contains("doesn't look like a till"));
    }

    @Test
    void otherPeopleAndOtherFilesGoOnToTheUsualRouting() {
        assertFalse(service.handle("254799999999", "document", "", document("video/mp4", "a.mp4")), "not a seller");
        assertFalse(service.handle(SELLER, "document", "", document("application/pdf", "price-list.pdf")), "not a video or photo");
        assertFalse(service.handle(SELLER, "text", "hello", null));
        assertFalse(service.handle(SELLER, "audio", "", Map.of("audio", Map.of("id", "X"))), "not a type we watermark");
    }

    @Test
    void theKillSwitchLetsPhotosAndVideosThroughToOtherRoutingButLeavesTillCommandsWorking() {
        SmmWatermarkService paused = new SmmWatermarkService(profiles, whatsapp, tillCheck, "ffmpeg-not-installed", "/fonts/DejaVuSans-Bold.ttf", 600, false);
        seller.setTillNumber("123456");
        seller.setTillName("Jane W. Enterprises");
        seller.setTillVerifiedAt(LocalDateTime.now());

        assertFalse(paused.handle(SELLER, "video", "", Map.of("video", Map.of("id", "MEDIA1", "mime_type", "video/mp4"))),
                "a ready seller's video still routes on while paused");
        assertFalse(paused.handle(SELLER, "image", "", Map.of("image", Map.of("id", "MEDIA1", "mime_type", "image/jpeg"))),
                "and so does a photo");
        verify(whatsapp, never()).sendText(eq(SELLER), contains("Got it"));

        assertTrue(paused.handle(SELLER, "text", "TILL 123456", null), "TILL commands keep working while media is paused");
    }

    @Test
    void aSellerWithoutATillIsToldWhatItIsForOnAPhotoToo() {
        assertTrue(service.handle(SELLER, "image", "", Map.of("image", Map.of("id", "MEDIA1", "mime_type", "image/jpeg"))));
        verify(whatsapp).sendText(eq(SELLER), contains("TILL 123456"));
        verify(whatsapp, never()).downloadMediaToFile(any(), any(), anyLong());
    }

    @Test
    void aReadySellersPhotoGetsThePhotoWordingNotTheVideoOne() {
        seller.setTillNumber("123456");
        seller.setTillName("Jane W. Enterprises");
        seller.setTillVerifiedAt(LocalDateTime.now());

        assertTrue(service.handle(SELLER, "image", "", Map.of("image", Map.of("id", "MEDIA1", "mime_type", "image/jpeg"))));
        verify(whatsapp).sendText(eq(SELLER), contains("photo"));
        verify(whatsapp, never()).sendText(eq(SELLER), contains("the video"));

        assertTrue(service.handle(SELLER, "document", "", document("image/jpeg", "poster.jpg")), "a photo sent as a Document is still a photo");
    }

    @Test
    void theWatermarkIsAnInstructionWithTheMpesaName() {
        seller.setTillNumber("123456");
        seller.setTillName("Jane W. Enterprises");
        seller.setTillVerifiedAt(LocalDateTime.now());
        assertEquals(List.of("Pay ONLY to Till 123456", "JANE W. ENTERPRISES", "We never send another number"),
                SmmWatermarkService.lines(seller));
    }

    @Test
    void theTextSitsHighAndMidFrameAndJumpsBetweenThem() {
        List<Path> files = List.of(Path.of("/tmp/wm/line0.txt"), Path.of("/tmp/wm/line1.txt"), Path.of("/tmp/wm/line2.txt"));
        List<String> cmd = SmmWatermarkService.command("ffmpeg", Path.of("/tmp/wm/in.video"), Path.of("/tmp/wm/out.mp4"),
                files, "C:\\fonts\\DejaVu.ttf", 28, 600);
        String vf = cmd.get(cmd.indexOf("-vf") + 1);
        assertEquals(3, vf.split("drawtext=").length - 1, "one centred line each");
        assertTrue(vf.contains("h*0.14") && vf.contains("h*0.40"), "upper third and middle, never the bottom or right edge");
        assertTrue(vf.contains("mod(t\\,8)\\,4"), "swaps place every 4 seconds");
        assertTrue(vf.contains("x=(w-text_w)/2"));
        assertTrue(vf.contains("fontfile='C\\:/fonts/DejaVu.ttf'"), "paths escaped for the filter");
        assertTrue(cmd.contains("libx264") && cmd.contains("+faststart"));
        assertEquals("/tmp/wm/out.mp4", cmd.get(cmd.size() - 1).replace('\\', '/'));
    }

    @Test
    void aPhotoGetsBothSpotsAtOnceSinceThereIsNoTimeToJumpBetweenThem() {
        List<Path> files = List.of(Path.of("/tmp/wm/line0.txt"), Path.of("/tmp/wm/line1.txt"), Path.of("/tmp/wm/line2.txt"));
        List<String> cmd = SmmWatermarkService.imageCommand("ffmpeg", Path.of("/tmp/wm/in.img"), Path.of("/tmp/wm/out.jpg"),
                files, "C:\\fonts\\DejaVu.ttf", 28);
        String vf = cmd.get(cmd.indexOf("-vf") + 1);
        assertEquals(6, vf.split("drawtext=").length - 1, "all 3 lines, burnt in at both the upper and the middle spot");
        assertTrue(vf.contains("h*0.14") && vf.contains("h*0.40"));
        assertFalse(vf.contains("mod(t"), "a still photo never jumps between the two spots");
        assertTrue(cmd.contains("-frames:v") && cmd.contains("1"), "exactly one frame out");
        assertEquals("/tmp/wm/out.jpg", cmd.get(cmd.size() - 1).replace('\\', '/'));
    }
}
