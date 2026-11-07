package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit test (no Spring, no database) for the end-of-day SMS text. */
class StockCountSmsTest {

    private static StockCountLine line(String name, Integer first, Integer counted, Integer expected,
                                       String cost, StockCountStatus status) {
        Integer variance = counted == null || expected == null ? null : counted - expected;
        BigDecimal value = variance == null ? null : new BigDecimal(cost).multiply(BigDecimal.valueOf(variance));
        return StockCountLine.builder()
                .itemName(name).firstCount(first).physicalQuantity(counted).expectedQuantity(expected)
                .varianceQuantity(variance).varianceValue(value).unitCost(new BigDecimal(cost)).status(status)
                .build();
    }

    @Test
    void countedTwiceOnlyWhenARecountWasSubmitted() {
        assertFalse(StockCountService.countedTwice(line("Beer", 45, 45, 45, "150", StockCountStatus.RECONCILED)));
        assertTrue(StockCountService.countedTwice(line("Whisky", 6, 6, 7, "2500", StockCountStatus.VARIANCE_OPEN)));
        assertTrue(StockCountService.countedTwice(line("Soda", 38, 40, 40, "30", StockCountStatus.RECONCILED)));

        StockCountLine skippedRecount = line("Gin", 18, 18, 20, "400", StockCountStatus.VARIANCE_OPEN);
        skippedRecount.setVarianceNote("Recount not done; first count used");
        assertFalse(StockCountService.countedTwice(skippedRecount));
    }

    @Test
    void summaryListsMissingExtraAndUncountedItems() {
        CloseDay day = CloseDay.builder()
                .tradingDate(LocalDate.of(2026, 10, 6))
                .totalSkusCounted(4).totalSkusQueued(5)
                .shortageValue(new BigDecimal("2500.00"))
                .closeNote("Gin shelf \"locked\"")
                .build();
        List<StockCountLine> lines = List.of(
                line("Test Beer 500ml", 45, 45, 45, "150", StockCountStatus.RECONCILED),
                line("Test Whisky 750ml", 6, 6, 7, "2500", StockCountStatus.VARIANCE_OPEN),
                line("Test Soda 300ml", 42, 42, 40, "30", StockCountStatus.VARIANCE_OPEN),
                line("Test Gin 250ml", null, null, 20, "400", StockCountStatus.NOT_COUNTED));

        String sms = StockCountService.buildSummarySms(day, lines);
        System.out.println(sms);

        assertTrue(sms.contains("4/5 items counted"));
        assertTrue(sms.contains("MISSING (KES 2500.00 at cost): Test Whisky 750ml -1 (sys 7, counted 6, counted twice)"));
        assertTrue(sms.contains("EXTRA: Test Soda 300ml +2 (counted twice)"));
        assertTrue(sms.contains("NOT COUNTED: Test Gin 250ml"));
        assertFalse(sms.contains("\""), "quotes would break the hand-built SMS JSON");
        assertFalse(sms.contains("Beer"), "matching items are not listed");
    }
}
