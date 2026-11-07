package com.dayworks_ltd.loyalty_engine.smm.model;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmmModelTest {

    @Test
    void readingIsCopiedOntoTheComment() {
        SmmComment c = SmmComment.builder().merchantId(1L).platform(SocialPlatform.FACEBOOK).commentId("123_456").text("Nataka size M 0702000000").build();
        assertEquals(SmmComment.State.NEW, c.getState());

        c.applyReading(new Reading(Intent.READY_TO_BUY, true, true, "DRESS-1", "green", "M", "sw", "high", false), true, false);

        assertEquals("READY_TO_BUY", c.getIntent());
        assertEquals("DRESS-1", c.getProductCode());
        assertEquals("green", c.getColour());
        assertEquals("M", c.getSize());
        assertTrue(c.getBuyer());
        assertTrue(c.getWantsWhatsApp());
        assertTrue(c.getPhoneFound());
        assertFalse(c.getNegative());
        assertFalse(c.getSecondLook());
        assertEquals(SmmComment.State.READ, c.getState());
    }

    @Test
    void badCommentFlagIsKept() {
        SmmComment c = SmmComment.builder().build();
        c.applyReading(new Reading(Intent.COMPLAINT, false, false, null, null, null, "sw", "high", true), false, true);
        assertTrue(c.getNegative());
        assertTrue(c.getSecondLook());
    }

    @Test
    void setListsItsItems() {
        SmmSet set = SmmSet.builder().name("3-in-1 suitcases").itemCodes("SC-20, SC-24 ,SC-28").setPrice(new BigDecimal("9500")).build();
        assertEquals(List.of("SC-20", "SC-24", "SC-28"), set.items());
        assertEquals(List.of(), set.optionalItems());
        assertTrue(set.isActive());
    }

    @Test
    void defaultsForANewSeller() {
        SmmSellerProfile p = SmmSellerProfile.builder().merchantId(7L).build();
        assertTrue(p.isShadowMode(), "a new seller starts in shadow mode: replies logged, not sent");
        assertFalse(p.isAgentsPaused());
        assertEquals("sw,en", p.getLanguages());

        assertEquals(SmmAccount.Status.ACTIVE, SmmAccount.builder().build().getStatus());
        assertEquals(SmmReply.Status.PENDING, SmmReply.builder().build().getStatus());
        assertFalse(SmmHandover.builder().build().isUrgent());
        assertEquals(SmmPostItem.TaggedBy.SELLER, SmmPostItem.builder().build().getTaggedBy());
    }

    @Test
    void servicesAreNotStockedGoods() {
        assertTrue(Inventory.builder().itemType(Inventory.ItemType.SERVICE).build().isService());
        assertFalse(Inventory.builder().build().isService(), "no type = a good, as every product before this change");
    }

    @Test
    void leadGetsItsRequiredTimesEvenFromTheBuilder() {
        Lead lead = Lead.builder().merchantId(1L).senderPsid("psid").platform(Lead.Platform.FACEBOOK)
                .source(Lead.LeadSource.COMMENT).temperature(Lead.Temperature.HOT).postId("reel_1").zoneMatch(true).build();
        lead.onCreate();
        assertNotNull(lead.getFirstContactAt());
        assertNotNull(lead.getLastContactAt());
        assertEquals(Lead.LeadStatus.NEW, lead.getStatus());
        assertEquals(Lead.Temperature.HOT, lead.getTemperature());
    }
}
