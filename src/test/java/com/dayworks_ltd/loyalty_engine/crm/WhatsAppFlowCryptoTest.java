package com.dayworks_ltd.loyalty_engine.crm;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.FlowCrypto;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Encrypts a request the way WhatsApp does and checks our side decrypts it and answers correctly. */
class WhatsAppFlowCryptoTest {

    @Test
    void decryptsWhatsAppRequestAndEncryptsResponseWithFlippedIv() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        // Our key as a PKCS#8 PEM file (what `openssl genpkey` writes), and the public half we'd give Meta
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        PrivateKey ours = FlowCrypto.readPrivateKey(pem);
        String publicPem = FlowCrypto.publicKeyPem(ours);
        PublicKey metaSide = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                .decode(publicPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""))));

        // WhatsApp client: random AES key + IV, AES-GCM body, RSA-OAEP(SHA-256) wrapped key
        byte[] aesKey = new byte[16];
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(aesKey);
        new SecureRandom().nextBytes(iv);
        String requestJson = "{\"action\":\"ping\",\"version\":\"3.0\"}";
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        String body = Base64.getEncoder().encodeToString(aes.doFinal(requestJson.getBytes(StandardCharsets.UTF_8)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, metaSide, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT));
        String wrappedKey = Base64.getEncoder().encodeToString(rsa.doFinal(aesKey));

        FlowCrypto.Decrypted decrypted = FlowCrypto.decrypt(body, wrappedKey, Base64.getEncoder().encodeToString(iv), ours);
        assertEquals(requestJson, decrypted.json());

        // WhatsApp decrypts our answer with the same key and the IV bits flipped
        String response = FlowCrypto.encryptResponse("{\"data\":{\"status\":\"active\"}}", decrypted);
        byte[] flipped = new byte[iv.length];
        for (int i = 0; i < iv.length; i++) flipped[i] = (byte) ~iv[i];
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, flipped));
        assertEquals("{\"data\":{\"status\":\"active\"}}",
                new String(aes.doFinal(Base64.getDecoder().decode(response)), StandardCharsets.UTF_8));
    }

    @Test
    void garbageRequestIsADecryptionError() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PrivateKey key = gen.generateKeyPair().getPrivate();
        assertThrows(FlowCrypto.DecryptionException.class, () -> FlowCrypto.decrypt("AAAA", "AAAA", "AAAA", key));
    }

    @Test
    void pkcs1KeysAreRejectedWithAHelpfulMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FlowCrypto.readPrivateKey("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"));
        assertTrue(e.getMessage().contains("openssl pkcs8"));
    }

    @Test
    void signatureMustMatchTheAppSecret() throws Exception {
        String body = "{\"object\":\"whatsapp_business_account\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String header = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertTrue(FlowCrypto.validSignature(body, header, "s3cret"));
        assertFalse(FlowCrypto.validSignature(body + " ", header, "s3cret"));
        assertFalse(FlowCrypto.validSignature(body, header, "other"));
        assertFalse(FlowCrypto.validSignature(body, null, "s3cret"));
        assertFalse(FlowCrypto.validSignature(body, header, ""), "no app secret configured = reject");
    }
}
