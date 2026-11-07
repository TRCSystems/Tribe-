package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner;
import com.dayworks_ltd.loyalty_engine.smm.meta.ConnectState;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.meta.SmmProperties;
import com.dayworks_ltd.loyalty_engine.smm.model.Lead;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmHandover;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.setup.SmmSetupController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmmPhase2Test {

    static final String SECRET = "test-secret-only";

    // ── connect ──

    @Test
    void connectStateRoundTripsAndCannotBeForgedChangedOrReplayed() {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        String state = ConnectState.create(990001L, 99001L, SECRET, now);
        ConnectState.Parsed who = ConnectState.verify(state, SECRET, now.plusSeconds(60));
        assertEquals(990001L, who.merchantId());
        assertEquals(99001L, who.userId());

        assertThrows(IllegalArgumentException.class, () -> ConnectState.verify(state, "another-secret", now), "forged");
        String otherShop = ConnectState.create(5L, 99001L, SECRET, now).split("\\.")[0] + "." + state.split("\\.")[1];
        assertThrows(IllegalArgumentException.class, () -> ConnectState.verify(otherShop, SECRET, now), "body swapped");
        assertThrows(IllegalArgumentException.class, () -> ConnectState.verify(state, SECRET, now.plusSeconds(31 * 60)), "too old");
        assertThrows(IllegalArgumentException.class, () -> ConnectState.verify(null, SECRET, now));
    }

    @Test
    void loginLinkAsksForThePagesAndCarriesTheState() {
        SmmProperties props = new SmmProperties();
        ReflectionTestUtils.setField(props, "appId", "4649633405362152");
        ReflectionTestUtils.setField(props, "graphVersion", "v21.0");
        ReflectionTestUtils.setField(props, "loginConfigId", "");
        ReflectionTestUtils.setField(props, "redirectUri", "https://tribessystems.co.ke/api/smm/connect/callback");
        String url = new MetaGraphClient(null, props).loginDialogUrl("abc.def");
        assertTrue(url.startsWith("https://www.facebook.com/v21.0/dialog/oauth?client_id=4649633405362152"));
        assertTrue(url.contains("state=abc.def"));
        assertTrue(url.contains("pages_manage_engagement") && url.contains("instagram_manage_comments"));

        ReflectionTestUtils.setField(props, "loginConfigId", "123");
        assertTrue(new MetaGraphClient(null, props).loginDialogUrl("s").contains("config_id=123"));
    }

    // ── webhook intake ──

    @Test
    void facebookFeedWebhookGivesNewCommentsOnly() {
        Map<String, Object> payload = Map.of("object", "page", "entry", List.of(Map.of("id", "PAGE1", "time", 1794000000,
                "changes", List.of(
                        Map.of("field", "feed", "value", Map.of("item", "comment", "verb", "add", "comment_id", "POST1_C1",
                                "post_id", "PAGE1_POST1", "parent_id", "PAGE1_POST1", "from", Map.of("id", "U1", "name", "Amina"),
                                "message", "Shingapi hii?", "created_time", 1794000000)),
                        Map.of("field", "feed", "value", Map.of("item", "comment", "verb", "edited", "comment_id", "POST1_C2")),
                        Map.of("field", "feed", "value", Map.of("item", "reaction", "verb", "add"))))));
        List<SmmIntakeService.Incoming> in = SmmIntakeService.parse(payload);
        assertEquals(1, in.size());
        SmmIntakeService.Incoming c = in.get(0);
        assertEquals(SocialPlatform.FACEBOOK, c.platform());
        assertEquals("PAGE1", c.accountId());
        assertEquals("POST1_C1", c.commentId());
        assertEquals("PAGE1_POST1", c.postId());
        assertNull(c.parentId(), "a top-level comment's parent is the post itself");
        assertEquals("Shingapi hii?", c.text());
        assertNotNull(c.createdAt());
    }

    @Test
    void instagramWebhookGivesTheComment() {
        Map<String, Object> payload = Map.of("object", "instagram", "entry", List.of(Map.of("id", "IG1", "time", 1794000000,
                "changes", List.of(Map.of("field", "comments", "value", Map.of("id", "IGC1", "text", "Mko wapi?",
                        "from", Map.of("id", "IGU1", "username", "wanjiku"), "media", Map.of("id", "MEDIA1")))))));
        SmmIntakeService.Incoming c = SmmIntakeService.parse(payload).get(0);
        assertEquals(SocialPlatform.INSTAGRAM, c.platform());
        assertEquals("MEDIA1", c.postId());
        assertEquals("wanjiku", c.fromName());
        assertTrue(SmmIntakeService.parse(Map.of("object", "whatsapp_business_account")).isEmpty());
    }

    // ── worker rules ──

    @Test
    void emojiOnlyIsPraiseWithoutAskingTheModel() {
        assertEquals(Intent.COMPLIMENT, SmmCommentWorker.ruleReading("😍🔥🔥").orElseThrow().intent());
        assertTrue(SmmCommentWorker.ruleReading("Shingapi 😍").isEmpty());
    }

    @Test
    void leadTemperature() {
        Reading price = new Reading(Intent.PRICE, true, false, null, null, null, "sw", "high");
        Reading wants = new Reading(Intent.READY_TO_BUY, true, false, null, null, null, "sw", "high");
        Reading praise = new Reading(Intent.COMPLIMENT, false, false, null, null, null, "en", "high");
        assertEquals(Lead.Temperature.WARM, SmmCommentWorker.temperature(price, false));
        assertEquals(Lead.Temperature.HOT, SmmCommentWorker.temperature(price, true), "left a number");
        assertEquals(Lead.Temperature.HOT, SmmCommentWorker.temperature(wants, false));
        assertEquals(Lead.Temperature.COLD, SmmCommentWorker.temperature(praise, false));
    }

    @Test
    void handoverReasons() {
        assertEquals(SmmHandover.Reason.NEGATIVE, SmmCommentWorker.reason(new Reading(Intent.OTHER, false, false, null, null, null, "sw", "high", true)));
        assertEquals(SmmHandover.Reason.AFTER_SALES, SmmCommentWorker.reason(new Reading(Intent.AFTER_SALES, false, false, null, null, null, "sw", "high")));
        assertEquals(SmmHandover.Reason.BUSINESS_LEAD, SmmCommentWorker.reason(new Reading(Intent.BUSINESS_LEAD, true, false, null, null, null, "sw", "high")));
    }

    @Test
    void politeCriticismStaysVisible() {
        Reading critic = new Reading(Intent.PRICE, true, false, null, null, null, "en", "high", false, true);
        CommentReplyPlanner.Plan p = CommentReplyPlanner.plan("You need to post the price", critic,
                List.of(new CommentReplyPlanner.Item("D1", "Dress", null, null, new BigDecimal("1500"), 3)),
                new CommentReplyPlanner.Shop("Shop", "CBD", null, null), Duration.ofMinutes(1));
        assertFalse(p.hideComment(), "polite criticism is not hidden");
        assertFalse(p.urgent());
        assertNotNull(p.publicReply(), "they still get their answer");
    }

    // ── alerts and setup ──

    @Test
    void alertTemplateParams() {
        assertEquals(List.of("A bad comment", "Facebook"), SmmAlertService.templateParams(1, EnumSet.of(SocialPlatform.FACEBOOK)));
        assertEquals(List.of("3 bad comments", "Facebook and Instagram"),
                SmmAlertService.templateParams(3, EnumSet.of(SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM)));
        Map<String, Object> def = SmmAlertService.templateDefinition("en");
        assertEquals("UTILITY", def.get("category"));
        assertEquals(SmmAlertService.TEMPLATE, def.get("name"));
    }

    @Test
    void captionSuggestsProducts() {
        List<Inventory> catalogue = List.of(
                Inventory.builder().itemCode("DRESS-1").itemName("Satin wrap dress").build(),
                Inventory.builder().itemCode("DRESS-1-G-M").itemName("Satin wrap dress").parentCode("DRESS-1").build(),
                Inventory.builder().itemCode("BAG-2").itemName("Leather tote").build());
        assertEquals(List.of("DRESS-1"), SmmSetupController.suggest("New in! Satin wrap dress KES 3500 🔥", catalogue));
        assertEquals(List.of(), SmmSetupController.suggest(null, catalogue));
    }
}
