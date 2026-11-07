package com.dayworks_ltd.loyalty_engine.smm.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenCipherTest {

    @AfterEach
    void reset() {
        TokenCipher.configure(null);
    }

    @Test
    void roundTripAndNeverStoredInPlain() {
        TokenCipher.configure("test-secret-only-for-this-test");
        String token = "EAAG-fake-page-token-123";
        String stored = TokenCipher.encrypt(token);

        assertTrue(stored.startsWith("v1:"));
        assertFalse(stored.contains(token));
        assertEquals(token, TokenCipher.decrypt(stored));
        assertNotEquals(stored, TokenCipher.encrypt(token), "a fresh IV every time");
    }

    @Test
    void converterUsesTheCipher() {
        TokenCipher.configure("another-test-secret");
        EncryptedStringConverter c = new EncryptedStringConverter();
        String column = c.convertToDatabaseColumn("page-token");
        assertEquals("page-token", c.convertToEntityAttribute(column));
        assertNull(c.convertToDatabaseColumn(null));
        assertNull(c.convertToEntityAttribute(null));
    }

    @Test
    void wrongSecretOrTamperedValueIsRefused() {
        TokenCipher.configure("secret-one");
        String stored = TokenCipher.encrypt("page-token");

        TokenCipher.configure("secret-two");
        assertThrows(IllegalStateException.class, () -> TokenCipher.decrypt(stored));

        TokenCipher.configure("secret-one");
        String tampered = stored.substring(0, stored.length() - 4) + (stored.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThrows(IllegalStateException.class, () -> TokenCipher.decrypt(tampered));
        assertThrows(IllegalStateException.class, () -> TokenCipher.decrypt("plain-token"), "plain text is never accepted");
    }

    @Test
    void withoutASecretNothingIsStored() {
        TokenCipher.configure("");
        assertFalse(TokenCipher.isConfigured());
        assertThrows(IllegalStateException.class, () -> TokenCipher.encrypt("page-token"));
    }
}
