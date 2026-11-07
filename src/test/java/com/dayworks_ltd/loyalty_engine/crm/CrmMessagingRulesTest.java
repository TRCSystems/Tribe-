package com.dayworks_ltd.loyalty_engine.crm;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmMessagingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests (no Spring, no database) for CRM message rules. */
class CrmMessagingRulesTest {

    private static CrmMessagingService rules() {
        CrmMessagingService s = new CrmMessagingService(null, null, null, java.util.List.of(), null);
        ReflectionTestUtils.setField(s, "quietStart", "19:00");
        ReflectionTestUtils.setField(s, "quietEnd", "08:00");
        ReflectionTestUtils.setField(s, "holdoutPercent", 10);
        return s;
    }

    @Test
    void marketingTemplatesCarryStopFooterAndOrderUpdatesDoNot() {
        String marketing = CrmTemplate.WEEKEND_STOCKUP.render(Map.of(
                "distributor", "Asano", "shop", "Mama Njeri", "items", "5x County 1/4", "link", "https://x/o/1"));
        assertEquals("Asano: Hey Mama Njeri, weekend imefika! Stock up early - your usual 5x County 1/4. "
                + "Order here: https://x/o/1 Reply STOP to opt out.", marketing);

        String update = CrmTemplate.ORDER_DELIVERED.render(Map.of("distributor", "Asano", "shop", "Mama Njeri", "count", "#123"));
        assertFalse(update.contains("STOP"));
    }

    @Test
    void unfilledPlaceholdersAreRemoved() {
        String s = CrmTemplate.REORDER_DUE.render(Map.of("distributor", "Asano", "shop", "Mama Njeri"));
        assertFalse(s.contains("{"), s);
    }

    @Test
    void quietHoursWrapAroundMidnight() {
        CrmMessagingService s = rules();
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(s, "inQuietHours", LocalTime.of(21, 0)));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(s, "inQuietHours", LocalTime.of(6, 30)));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(s, "inQuietHours", LocalTime.of(10, 0)));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(s, "inQuietHours", LocalTime.of(18, 59)));
    }

    @Test
    void holdoutIsStableAndRoughlyTenPercent() {
        CrmMessagingService s = rules();
        long held = LongStream.rangeClosed(1, 2000).filter(shop -> s.isHoldout(23L, shop)).count();
        assertTrue(held > 120 && held < 280, "held out " + held + " of 2000");
        assertEquals(s.isHoldout(23L, 77L), s.isHoldout(23L, 77L));
    }
}
