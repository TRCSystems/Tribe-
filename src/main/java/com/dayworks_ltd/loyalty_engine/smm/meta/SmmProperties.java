package com.dayworks_ltd.loyalty_engine.smm.meta;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for connecting sellers' Facebook Pages and Instagram accounts (the tribeflow Meta app).
 * The app secret and Graph version fall back to the WhatsApp ones: it is the same Meta app.
 */
@Getter
@Component
public class SmmProperties {

    @Value("${smm.meta.app-id:}")
    private String appId;

    @Value("${smm.meta.app-secret:${whatsapp.app-secret:}}")
    private String appSecret;

    @Value("${smm.meta.graph-version:${whatsapp.graph-version:v21.0}}")
    private String graphVersion;

    /** Facebook Login for Business configuration ID (the set of permissions the seller is asked for). */
    @Value("${smm.meta.login-config-id:}")
    private String loginConfigId;

    /** Where Facebook sends the seller back; must be registered in the app. nginx maps /api/ to /api/v1/. */
    @Value("${smm.meta.redirect-uri:https://tribessystems.co.ke/api/smm/connect/callback}")
    private String redirectUri;

    /** Token Meta echoes when the webhook URL is registered. */
    @Value("${smm.meta.verify-token:}")
    private String verifyToken;

    /** Portal address the seller lands on after connecting, and the link in seller alerts. */
    @Value("${smm.portal-url:https://tribessystems.co.ke}")
    private String portalUrl;

    /** Signs the connect state; defaults to the token secret. */
    @Value("${smm.state-secret:${smm.token-secret:}}")
    private String stateSecret;

    public String graph() {
        return "https://graph.facebook.com/" + graphVersion + "/";
    }

    public List<String> missingForConnect() {
        List<String> missing = new ArrayList<>();
        if (appId.isBlank()) missing.add("smm.meta.app-id");
        if (appSecret.isBlank()) missing.add("smm.meta.app-secret (or whatsapp.app-secret)");
        if (stateSecret.isBlank()) missing.add("smm.token-secret");
        return missing;
    }
}
