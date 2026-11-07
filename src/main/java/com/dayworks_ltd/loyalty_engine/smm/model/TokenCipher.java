package com.dayworks_ltd.loyalty_engine.smm.model;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets kept in the database (Facebook / Instagram Page access tokens) with AES-256-GCM.
 * The key comes from the server config ({@code smm.token-secret}), never from the database, so a copied
 * database alone does not give away anyone's Page. Stored form: {@code v1:<base64(iv | ciphertext+tag)>}.
 * Without a secret configured, storing or reading a token fails rather than falling back to plain text.
 */
public final class TokenCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12, TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile SecretKeySpec key;

    private TokenCipher() {}

    /** Called once at startup with the configured secret (any length; it is hashed to a 256-bit key). */
    public static void configure(String secret) {
        if (secret == null || secret.isBlank()) {
            key = null;
            return;
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not set up token encryption", e);
        }
    }

    public static boolean isConfigured() {
        return key != null;
    }

    public static String encrypt(String plain) {
        if (plain == null) return null;
        SecretKeySpec k = requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the token", e);
        }
    }

    public static String decrypt(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(PREFIX)) throw new IllegalStateException("Stored token is not encrypted");
        SecretKeySpec k = requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong secret or a changed value: never return something half-read
            throw new IllegalStateException("Could not decrypt the token (wrong smm.token-secret or tampered value)");
        }
    }

    private static SecretKeySpec requireKey() {
        SecretKeySpec k = key;
        if (k == null) throw new IllegalStateException("smm.token-secret is not set: Page tokens cannot be stored or read");
        return k;
    }
}
