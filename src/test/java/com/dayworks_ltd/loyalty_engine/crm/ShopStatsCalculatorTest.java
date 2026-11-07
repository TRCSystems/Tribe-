package com.dayworks_ltd.loyalty_engine.crm;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats.Segment;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator.AllTime;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator.LineFact;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator.OrderFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests (no Spring, no database) for shop segments and buying rhythm. */
class ShopStatsCalculatorTest {

    private static final Long DIST = 23L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 10, 0);

    private static OrderFact order(long shop, int daysAgo, int kes, String... items) {
        List<LineFact> lines = Arrays.stream(items).map(i -> new LineFact(i, i, 2)).toList();
        return new OrderFact(shop, NOW.minusDays(daysAgo), BigDecimal.valueOf(kes), false, false, lines);
    }

    /** One order every `every` days, the most recent `lastDaysAgo` days ago. */
    private static List<OrderFact> rhythm(long shop, int every, int lastDaysAgo, int count, int kes, String... items) {
        List<OrderFact> l = new ArrayList<>();
        for (int i = 0; i < count; i++) l.add(order(shop, lastDaysAgo + i * every, kes, items));
        return l;
    }

    private static Map<Long, CrmShopStats> run(Collection<Long> linked, List<OrderFact> orders) {
        Map<Long, AllTime> allTime = new HashMap<>();
        orders.stream().filter(o -> !o.cancelled()).forEach(o -> allTime.merge(o.shopId(), new AllTime(o.date(), 1),
                (a, b) -> new AllTime(a.firstOrder().isBefore(b.firstOrder()) ? a.firstOrder() : b.firstOrder(), a.orders() + b.orders())));
        Map<Long, CrmShopStats> out = new HashMap<>();
        ShopStatsCalculator.compute(DIST, linked, orders, allTime, Map.of(), 8, NOW)
                .forEach(s -> out.put(s.getShopMerchantId(), s));
        return out;
    }

    @Test
    void segmentsFollowEachShopsOwnRhythm() {
        List<OrderFact> orders = new ArrayList<>();
        orders.addAll(rhythm(1, 6, 2, 10, 4000, "COUNTY"));          // weekly-ish, ordered 2 days ago → ACTIVE
        orders.addAll(rhythm(2, 6, 6, 10, 4000, "COUNTY"));          // due today → DUE
        orders.addAll(rhythm(3, 6, 12, 10, 4000, "COUNTY"));         // 2x gap overdue → AT_RISK
        orders.addAll(rhythm(4, 6, 40, 10, 4000, "COUNTY"));         // long gone → LOST
        orders.addAll(rhythm(5, 3, 1, 25, 6000, "COUNTY", "KIBAO")); // frequent and big → CHAMPION
        orders.add(order(6, 10, 3000, "COUNTY"));                    // first order 10 days ago → NEW

        Map<Long, CrmShopStats> s = run(List.of(7L), orders);        // shop 7 linked, never ordered

        assertEquals(Segment.ACTIVE, s.get(1L).getSegment());
        assertEquals(Segment.DUE, s.get(2L).getSegment());
        assertEquals(Segment.AT_RISK, s.get(3L).getSegment());
        assertEquals(Segment.LOST, s.get(4L).getSegment());
        assertEquals(Segment.CHAMPION, s.get(5L).getSegment());
        assertEquals(Segment.NEW, s.get(6L).getSegment());
        assertEquals(Segment.PROSPECT, s.get(7L).getSegment());
        assertFalse(s.containsKey(DIST), "the distributor itself is not one of its shops");
    }

    @Test
    void usualGapAndNextExpectedOrder() {
        Map<Long, CrmShopStats> s = run(List.of(), rhythm(1, 5, 2, 8, 4000, "COUNTY"));
        CrmShopStats one = s.get(1L);
        assertEquals(5.0, one.getUsualGapDays());
        assertFalse(one.getGapIsEstimated());
        assertEquals(NOW.toLocalDate().minusDays(2).plusDays(5), one.getNextExpectedOrderDate());
    }

    @Test
    void crossSellSuggestsWhatMostShopsBuyButThisOneDoesNot() {
        List<OrderFact> orders = new ArrayList<>();
        for (long shop = 1; shop <= 4; shop++) orders.addAll(rhythm(shop, 6, 2, 4, 4000, "COUNTY", "KIBAO"));
        orders.addAll(rhythm(5, 6, 2, 4, 4000, "COUNTY"));            // shop 5 never buys KIBAO
        Map<Long, CrmShopStats> s = run(List.of(), orders);
        assertTrue(s.get(5L).getCrossSellJson().contains("KIBAO"), s.get(5L).getCrossSellJson());
        assertEquals("[]", s.get(1L).getCrossSellJson());
    }

    @Test
    void consignmentReadyNeedsEnoughSelfOrdersAndNoCancellations() {
        List<OrderFact> orders = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            orders.add(new OrderFact(1L, NOW.minusDays(2 + i * 5), BigDecimal.valueOf(4000), false, true,
                    List.of(new LineFact("COUNTY", "COUNTY", 2))));
            orders.add(new OrderFact(2L, NOW.minusDays(2 + i * 5), BigDecimal.valueOf(4000), false, true,
                    List.of(new LineFact("COUNTY", "COUNTY", 2))));
        }
        orders.add(new OrderFact(2L, NOW.minusDays(3), BigDecimal.valueOf(4000), true, true, List.of()));
        Map<Long, CrmShopStats> s = run(List.of(), orders);
        assertTrue(s.get(1L).getConsignmentReady());
        assertFalse(s.get(2L).getConsignmentReady(), "a cancellation in 60 days blocks it");
    }
}
