package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Item;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The WhatsApp link a reply carries: which item it's pre-typed for, and the message text — the same "Hi, nataka
 * suitcase 20kg white @1,500" shape the plan describes, so the seller sees exactly what the buyer wants.
 */
class SmmCommentWorkerShopLinkTest {

    static final Item DRESS = new Item("DRESS", "Dress", "Green", "M", new BigDecimal("1500.00"), 5);
    static final Item SHOES = new Item("SHOES", "Shoes", null, "42", new BigDecimal("2500"), 2);

    @Test
    void theOnlyItemOnAPostIsUsedWhenNothingElseMatches() {
        Item matched = SmmCommentWorker.matchedItem(List.of(DRESS), new Reading(null, true, false, null, null, null, null, "high"));
        assertEquals(DRESS, matched);
    }

    @Test
    void theItemTheReaderNamedWinsOverMultipleItems() {
        Item matched = SmmCommentWorker.matchedItem(List.of(DRESS, SHOES),
                new Reading(null, true, false, "SHOES", null, null, null, "high"));
        assertEquals(SHOES, matched);
    }

    @Test
    void noMatchWithSeveralItemsAndNoClearOneGivesNull() {
        assertNull(SmmCommentWorker.matchedItem(List.of(DRESS, SHOES), new Reading(null, true, false, null, null, null, null, "low")));
        assertNull(SmmCommentWorker.matchedItem(List.of(), null));
    }

    @Test
    void theQueryIsPreTypedWithNameColourSizeAndPrice() {
        String q = SmmCommentWorker.preTypedQuery(DRESS);
        assertTrue(q.startsWith("?text="));
        String decoded = java.net.URLDecoder.decode(q.substring(6), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Hi, nataka Dress Green size M @ KES 1500", decoded);
    }

    @Test
    void noItemMeansNoQueryAtAll() {
        assertEquals("", SmmCommentWorker.preTypedQuery(null));
    }
}
