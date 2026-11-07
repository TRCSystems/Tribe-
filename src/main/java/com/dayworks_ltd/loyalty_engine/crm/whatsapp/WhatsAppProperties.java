package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the Tribe WhatsApp number (Meta Cloud API). Secrets live in the server config
 * (/root/TrcTenants/config), never in the repo.
 */
@Getter
@Component
public class WhatsAppProperties {

    /** Master switch: when false every CRM message goes by SMS. */
    @Value("${crm.whatsapp.enabled:false}")
    private boolean enabled;

    @Value("${whatsapp.graph-version:v21.0}")
    private String graphVersion;

    /** Phone number ID of the Tribe number (WhatsApp Manager → API setup). */
    @Value("${whatsapp.phone-number-id:}")
    private String phoneNumberId;

    /** WhatsApp Business Account ID — needed to create templates. */
    @Value("${whatsapp.waba-id:}")
    private String wabaId;

    /** Permanent System User token with whatsapp_business_messaging + whatsapp_business_management. */
    @Value("${whatsapp.access-token:}")
    private String accessToken;

    /** Meta app secret: verifies that webhook and flow calls really come from Meta. */
    @Value("${whatsapp.app-secret:}")
    private String appSecret;

    /** Any random string; typed into the Meta webhook settings as the "Verify token". */
    @Value("${whatsapp.verify-token:}")
    private String verifyToken;

    /** ID of the published "Tribe order" flow. */
    @Value("${whatsapp.flow-id:}")
    private String flowId;

    /** "draft" sends the unpublished version of the order form (testing); "published" for real use. */
    @Value("${whatsapp.flow-mode:published}")
    private String flowMode;

    /** PKCS#8 PEM private key whose public half is registered with Meta (flow encryption). */
    @Value("${whatsapp.flow-private-key-path:}")
    private String flowPrivateKeyPath;

    /** Language the templates were approved in. */
    @Value("${whatsapp.template-language:en}")
    private String templateLanguage;

    /** The Tribe WhatsApp number shops chat with, e.g. 254712345678 (for wa.me links in SMS). */
    @Value("${whatsapp.display-number:}")
    private String displayNumber;

    /** True when we can send (switch on and the number is configured). */
    public boolean canSend() {
        return enabled && !phoneNumberId.isBlank() && !accessToken.isBlank();
    }

    /** Names of required settings that are missing — shown on the admin status page. */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        if (!enabled) missing.add("crm.whatsapp.enabled=true");
        if (phoneNumberId.isBlank()) missing.add("whatsapp.phone-number-id");
        if (wabaId.isBlank()) missing.add("whatsapp.waba-id");
        if (accessToken.isBlank()) missing.add("whatsapp.access-token");
        if (appSecret.isBlank()) missing.add("whatsapp.app-secret");
        if (verifyToken.isBlank()) missing.add("whatsapp.verify-token");
        if (flowId.isBlank()) missing.add("whatsapp.flow-id");
        if (flowPrivateKeyPath.isBlank()) missing.add("whatsapp.flow-private-key-path");
        if (displayNumber.isBlank()) missing.add("whatsapp.display-number");
        return missing;
    }
}
