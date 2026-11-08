package com.dayworks_ltd.loyalty_engine.smm.meta;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/** Connecting Pages failed on 8 Nov 2026: the braces in Graph field lists broke the URL, and the error logged the token. */
class MetaGraphUriTest {

    @Test
    void fieldListsWithBracesMakeAValidUrl() {
        URI uri = MetaGraphClient.toUri(UriComponentsBuilder.fromUriString("https://graph.facebook.com/v21.0/me/accounts")
                .queryParam("fields", "id,name,access_token,instagram_business_account{id,username}")
                .queryParam("limit", 100)
                .queryParam("access_token", "EAATESTTOKEN123"));
        String s = uri.toString();
        assertTrue(s.contains("instagram_business_account%7Bid,username%7D"), s);
        assertTrue(s.endsWith("access_token=EAATESTTOKEN123"), s);
        assertFalse(s.contains("{"), s);
    }

    @Test
    void tokensNeverReachTheLogs() {
        String msg = "Could not create URI: https://graph.facebook.com/me/accounts?fields=id&access_token=EAATESTTOKEN&limit=100";
        assertEquals("Could not create URI: https://graph.facebook.com/me/accounts?fields=id&access_token=REDACTED&limit=100",
                MetaGraphClient.redact(msg));
        assertEquals("x?fb_exchange_token=REDACTED and code=REDACTED", MetaGraphClient.redact("x?fb_exchange_token=AAA and code=BBB"));
        assertNull(MetaGraphClient.redact(null));
    }

    @Test
    void pagesPickedInTheLoginDialogAreReadFromTheGrants() {
        java.util.Map<String, Object> data = java.util.Map.of(
                "scopes", java.util.List.of("pages_show_list", "pages_read_engagement", "instagram_basic"),
                "granular_scopes", java.util.List.of(
                        java.util.Map.of("scope", "pages_show_list", "target_ids", java.util.List.of("111", "222")),
                        java.util.Map.of("scope", "pages_read_engagement", "target_ids", java.util.List.of("222")),
                        java.util.Map.of("scope", "instagram_basic", "target_ids", java.util.List.of("17841")),
                        java.util.Map.of("scope", "business_management")));
        MetaGraphClient.Grants g = MetaGraphClient.grantsFrom(data);
        assertEquals(java.util.List.of("111", "222"), java.util.List.copyOf(g.pageIds()), "Pages only, each once");
        assertEquals(3, g.scopes().size());
        assertTrue(MetaGraphClient.grantsFrom(java.util.Map.of()).pageIds().isEmpty());
    }
}
