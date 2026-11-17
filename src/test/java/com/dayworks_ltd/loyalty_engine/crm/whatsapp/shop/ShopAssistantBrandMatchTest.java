package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The brand-matching helpers behind the B2B liquor order guards. A real production failure (16 Nov 2026): "KC 1/4
 * 12 ITEMS" read correctly by the model as the catalogue's "KC1/4" was rejected by {@link ShopAssistant#plausibleBrandMatch},
 * because normalize() removed the "/" before the size pattern could strip it from the glued catalogue name, leaving a
 * bogus "kc1" token, and "items" was never recognised as filler.
 */
class ShopAssistantBrandMatchTest {

    @Test
    void aShortBrandAbbreviationGluedToItsSizeInTheCatalogueStillMatchesTheCustomersWords() {
        assertTrue(ShopAssistant.plausibleBrandMatch("KC 1/4 12 ITEMS", "KC1/4"));
        assertTrue(ShopAssistant.plausibleBrandMatch("1 KC 3/4 @703", "KC 3/4"));
        assertTrue(ShopAssistant.plausibleBrandMatch("kc kota 12 items", "KC1/4"));
    }

    @Test
    void quantityWordsAreNeverMistakenForABrand() {
        assertEquals(java.util.Set.of(), ShopAssistant.brandWords("12 items"));
        assertEquals(java.util.Set.of(), ShopAssistant.brandWords("5 pieces"));
    }

    @Test
    void realMismatchesAreStillCaught() {
        assertFalse(ShopAssistant.plausibleBrandMatch("3/4 Kenya cane -2", "BLACK & WHITE 1/2"));
        assertFalse(ShopAssistant.plausibleBrandMatch("Kibao 1/4", "COUNTY 1/4"));
    }

    @Test
    void wordsThatRunTogetherAcrossSidesStillMatch() {
        assertTrue(ShopAssistant.plausibleBrandMatch("1/4 Vice Roy -1", "VICEROY 1/4"));
    }

    @Test
    void gluedBrandAndSizeCatalogueNamesAreRecognisedAsTheSameProduct() {
        assertTrue(ShopAssistant.sameProduct(List.of("KC1/4", "KC 3/4", "KC 1/2")));
    }
}
