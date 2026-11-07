package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * WhatsApp Flows endpoint encryption (Meta spec): the request's AES-128 key is RSA-OAEP(SHA-256)
 * encrypted with our public key; the body is AES-GCM. We answer with the same AES key and the
 * IV with every bit flipped.
 */
public final class FlowCrypto {

    private FlowCrypto() {}

    /** A decrypted request plus what is needed to encrypt the reply. */
    public record Decrypted(String json, byte[] aesKey, byte[] iv) {}

    /** Thrown when the request can't be decrypted — answered with HTTP 421 so WhatsApp refreshes our key. */
    public static class DecryptionException extends RuntimeException {
        public DecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Decrypted decrypt(String encryptedFlowData, String encryptedAesKey, String initialVector,
                                    PrivateKey privateKey) {
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.DECRYPT_MODE, privateKey, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] aesKey = rsa.doFinal(Base64.getDecoder().decode(encryptedAesKey));
            byte[] iv = Base64.getDecoder().decode(initialVector);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] plain = aes.doFinal(Base64.getDecoder().decode(encryptedFlowData));
            return new Decrypted(new String(plain, StandardCharsets.UTF_8), aesKey, iv);
        } catch (Exception e) {
            throw new DecryptionException("Could not decrypt flow request", e);
        }
    }

    /** Base64 response body for a decrypted request. */
    public static String encryptResponse(String json, Decrypted request) {
        try {
            byte[] flipped = new byte[request.iv().length];
            for (int i = 0; i < flipped.length; i++) flipped[i] = (byte) ~request.iv()[i];
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(request.aesKey(), "AES"), new GCMParameterSpec(128, flipped));
            return Base64.getEncoder().encodeToString(aes.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt flow response", e);
        }
    }

    /** Reads an unencrypted PKCS#8 PEM ("BEGIN PRIVATE KEY", e.g. from `openssl genpkey`). */
    public static PrivateKey readPrivateKey(String pem) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException("Key is PKCS#1; convert it with: openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
        }
        try {
            String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid PKCS#8 RSA private key", e);
        }
    }

    /** The public half as PEM — what gets registered with Meta. */
    public static String publicKeyPem(PrivateKey privateKey) {
        try {
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
            byte[] der = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())).getEncoded();
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
            return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive the public key", e);
        }
    }

    /** True when X-Hub-Signature-256 ("sha256=<hex>") matches the raw body signed with the app secret. */
    public static boolean validSignature(String rawBody, String header, String appSecret) {
        if (header == null || !header.startsWith("sha256=") || appSecret == null || appSecret.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] given = HexFormat.of().parseHex(header.substring(7));
            return MessageDigest.isEqual(expected, given);
        } catch (Exception e) {
            return false;
        }
    }
}
