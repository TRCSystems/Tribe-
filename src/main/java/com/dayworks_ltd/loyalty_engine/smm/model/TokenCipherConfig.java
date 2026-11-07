package com.dayworks_ltd.loyalty_engine.smm.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hands the configured {@code smm.token-secret} to {@link TokenCipher} at startup. */
@Slf4j
@Component
public class TokenCipherConfig {

    public TokenCipherConfig(@Value("${smm.token-secret:}") String secret) {
        TokenCipher.configure(secret);
        if (!TokenCipher.isConfigured()) {
            log.warn("smm.token-secret is not set: Facebook/Instagram Pages cannot be connected until it is");
        }
    }
}
