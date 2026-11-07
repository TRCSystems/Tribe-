package com.dayworks_ltd.loyalty_engine.smm.meta;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * The {@code state} passed through Facebook's login and back: which merchant and user started the connection.
 * It is signed and expires after 30 minutes, so a forged or replayed callback cannot attach a Page to another shop.
 * Form: base64url("merchantId.userId.expiresEpochSeconds") + "." + base64url(HMAC-SHA256).
 */
public final class ConnectState {

    public record Parsed(long merchantId, long userId) {}

    static final long TTL_SECONDS = 30 * 60;

    private ConnectState() {}

    public static String create(long merchantId, long userId, String secret, Instant now) {
        String payload = merchantId + "." + userId + "." + (now.getEpochSecond() + TTL_SECONDS);
        String body = b64(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + b64(hmac(body, secret));
    }

    /** The merchant and user, or an exception when the state is forged, changed or out of date. */
    public static Parsed verify(String state, String secret, Instant now) {
        if (state == null || !state.contains(".")) throw new IllegalArgumentException("Missing connect state");
        int dot = state.lastIndexOf('.');
        String body = state.substring(0, dot);
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(state.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bad connect state");
        }
        if (!MessageDigest.isEqual(given, hmac(body, secret))) throw new IllegalArgumentException("Connect state was changed");
        String[] parts = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Bad connect state");
        if (now.getEpochSecond() > Long.parseLong(parts[2])) throw new IllegalArgumentException("The connection took too long; please start again");
        return new Parsed(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    private static byte[] hmac(String body, String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("smm.token-secret is not set");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("connect-state:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the connect state", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
