package com.dayworks_ltd.loyalty_engine.inventory.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit test (no Spring, no database) for the end-of-month SMS. */
class MonthlySummaryMessageTest {

    private static SalesSummaryService.Summary s(String revenue, String margin, String pct, int sales, int noCost) {
        return new SalesSummaryService.Summary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), sales,
                new BigDecimal(revenue), new BigDecimal(margin), pct == null ? null : new BigDecimal(pct), noCost);
    }

    @Test
    void saysWhatWasSoldMadeAndClosed() {
        String msg = SalesSummaryService.monthlyMessage("Asano", YearMonth.of(2026, 9),
                s("601983", "25477", "4.2", 142, 0), s("537485", "20000", "3.7", 130, 0), new BigDecimal("1234567.40"));
        assertEquals("TRIBE Asano: September 2026 summary. Sold KES 601,983 (142 sales, +12% vs last month). "
                + "Made KES 25,477 (4.2% margin). Closing stock KES 1,234,567 at cost.", msg);
    }

    @Test
    void mentionsItemsLeftOutOfTheMarginAndSkipsComparisonWithoutHistory() {
        String msg = SalesSummaryService.monthlyMessage("Shop \"A\"", YearMonth.of(2026, 10),
                s("1000", "100", "10.0", 3, 2), s("0", "0", null, 0, 0), BigDecimal.ZERO);
        assertTrue(msg.contains("excl. 2 items with no cost"), msg);
        assertFalse(msg.contains("vs last month"), msg);
        assertFalse(msg.contains("\""), "quotes would break the hand-built SMS JSON");
    }
}
