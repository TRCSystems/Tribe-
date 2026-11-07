package com.dayworks_ltd.loyalty_engine.smm.comments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SellerAlertTest {

    static final String PORTAL = "https://tribessystems.co.ke";

    @Test
    void oneBadCommentInEnglish() {
        assertEquals("⚠️ Tribe: A bad comment needing your intervention was received on Facebook. It has been hidden from the public.\n"
                + "Please log in to Tribe to see the details and act promptly: https://tribessystems.co.ke",
                SellerAlert.badComments(1, "Facebook", PORTAL, false));
    }

    @Test
    void severalAreGroupedWithTheCount() {
        String alert = SellerAlert.badComments(4, "Instagram", PORTAL, false);
        assertTrue(alert.startsWith("⚠️ Tribe: 4 bad comments needing your intervention were received on Instagram."));
        assertTrue(alert.contains("They have been hidden from the public."));
    }

    @Test
    void swahiliForSellersWhoChoseIt() {
        String alert = SellerAlert.badComments(1, "Facebook", PORTAL, true);
        assertTrue(alert.contains("Umepokea maoni mabaya 1 kwenye Facebook"));
        assertTrue(alert.contains("yasionekane na umma"));
        assertTrue(alert.endsWith(PORTAL));
    }

    @Test
    void neverCarriesTheCommentItself() {
        // The method takes no comment text or commenter: the details are only in the portal
        String alert = SellerAlert.badComments(2, "Facebook", PORTAL, false);
        assertFalse(alert.matches("(?s).*\\d{9,}.*"), "no phone numbers");
        assertTrue(alert.length() < 300, "a short notification");
    }
}
