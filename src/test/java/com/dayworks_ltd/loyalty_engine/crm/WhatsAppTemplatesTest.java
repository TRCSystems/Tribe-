package com.dayworks_ltd.loyalty_engine.crm;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Meta rejects templates that break its formatting rules — catch that here, not after a 24h review. */
class WhatsAppTemplatesTest {

    @Test
    void everyTemplateFollowsMetaRules() {
        for (WhatsAppTemplates.WaTemplate t : WhatsAppTemplates.all()) {
            String text = t.metaText();
            assertTrue(t.name().matches("[a-z0-9_]{1,512}"), t.name());
            assertFalse(text.startsWith("{{"), t.name() + " starts with a variable");
            assertFalse(text.trim().matches(".*\\{\\{\\d+}}[.!?]?$"), t.name() + " ends with a variable");
            assertFalse(text.matches(".*}}\\s*\\{\\{.*"), t.name() + " has variables side by side");
            assertFalse(text.contains("{") && text.replaceAll("\\{\\{\\d+}}", "").contains("{"), t.name() + " has a leftover placeholder");
            assertTrue(text.length() <= 1024, t.name() + " too long");
            assertTrue(t.variables().contains("distributor"), t.name() + " must name the distributor (shared number)");
        }
    }

    @Test
    void everyCrmTemplateExceptFreeTextAndTheInviteHasAWhatsAppVersion() {
        for (CrmTemplate t : CrmTemplate.values()) {
            if (t == CrmTemplate.CUSTOM || t == CrmTemplate.WHATSAPP_INVITE) {
                assertTrue(WhatsAppTemplates.forTemplate(t).isEmpty());
                continue;
            }
            WhatsAppTemplates.WaTemplate wa = WhatsAppTemplates.forTemplate(t).orElseThrow();
            assertEquals(t.isMarketing(), wa.marketing(), t.name());
            assertEquals(t.needsLink(), wa.orderButton(), t.name() + ": order button iff the SMS has a link");
        }
    }

    @Test
    void paramsFollowPlaceholderOrderAndBlanksGetAWord() {
        WhatsAppTemplates.WaTemplate t = WhatsAppTemplates.forTemplate(CrmTemplate.WEEKEND_STOCKUP).orElseThrow();
        assertEquals(List.of("shop", "items", "distributor"), t.variables());
        assertEquals("Hey {{1}}, weekend imefika! Stock up early with your usual {{2}} from {{3}}. Tap Order now below.",
                t.metaText());
        assertEquals(List.of("Mama Njeri", "favourites", "Asano"),
                t.params(Map.of("shop", "Mama Njeri", "items", " ", "distributor", "Asano\n")));
    }

    @Test
    void marketingDefinitionHasStopFooterAndFlowButton() {
        WhatsAppTemplates.WaTemplate t = WhatsAppTemplates.forTemplate(CrmTemplate.PROMO).orElseThrow();
        String def = WhatsAppTemplates.metaDefinition(t, "en", "123").toString();
        assertTrue(def.contains("MARKETING"));
        assertTrue(def.contains(WhatsAppTemplates.STOP_FOOTER));
        assertTrue(def.contains("flow_id=123"));
        assertTrue(def.contains("example"));

        WhatsAppTemplates.WaTemplate utility = WhatsAppTemplates.forTemplate(CrmTemplate.ORDER_DISPATCHED).orElseThrow();
        String u = WhatsAppTemplates.metaDefinition(utility, "en", "123").toString();
        assertTrue(u.contains("UTILITY"));
        assertFalse(u.contains("FLOW"));
        assertFalse(u.contains("FOOTER"));
    }

    @Test
    void phoneNumbersBecomeWhatsAppFormat() {
        assertEquals("254712345678", WhatsAppCloudClient.toWaNumber("0712345678"));
        assertEquals("254712345678", WhatsAppCloudClient.toWaNumber("+254 712 345 678"));
        assertEquals("254112345678", WhatsAppCloudClient.toWaNumber("0112345678"));
        assertEquals("254712345678", WhatsAppCloudClient.toWaNumber("712345678"));
        assertNull(WhatsAppCloudClient.toWaNumber("12345"));
        assertNull(WhatsAppCloudClient.toWaNumber(null));
    }
}
