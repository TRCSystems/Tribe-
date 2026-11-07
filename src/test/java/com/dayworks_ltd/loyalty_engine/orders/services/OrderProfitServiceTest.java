package com.dayworks_ltd.loyalty_engine.orders.services;

import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit test (no Spring, no database) for the distributor's margin per order. */
class OrderProfitServiceTest {

    private static OrderItem line(long id, String code, int qty, String price) {
        return OrderItem.builder().id(id).itemCode(code).quantity(qty)
                .wholesalePrice(new BigDecimal(price))
                .lineTotal(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)))
                .build();
    }

    private static Order order(OrderItem... items) {
        Order o = new Order();
        o.setItems(new ArrayList<>());
        for (OrderItem i : items) o.addItem(i);
        return o;
    }

    @Test
    void marginIsWholesalePriceMinusUnitCostTimesQuantity() {
        // 10 x County at 242 (cost 200) + 5 x Kibao at 236 (cost 190)
        Order o = order(line(1, "COUNTY", 10, "242"), line(2, "KIBAO", 5, "236"));
        OrderProfitService.Profit p = OrderProfitService.profit(o,
                Map.of("COUNTY", new BigDecimal("200"), "KIBAO", new BigDecimal("190")));

        assertEquals(new BigDecimal("3600.00"), p.revenueKes());
        assertEquals(new BigDecimal("2950.00"), p.costKes());
        assertEquals(new BigDecimal("650.00"), p.marginKes());
        assertEquals(new BigDecimal("18.1"), p.marginPct());
        assertEquals(new BigDecimal("420.00"), p.lines().get(1L).lineMargin());
        assertEquals(new BigDecimal("230.00"), p.lines().get(2L).lineMargin());
        assertTrue(p.marginComplete());
    }

    @Test
    void missingCostLeavesMarginEmptyInsteadOfOverstatingIt() {
        Order o = order(line(1, "COUNTY", 10, "242"), line(2, "UNKNOWN", 1, "500"));
        OrderProfitService.Profit p = OrderProfitService.profit(o, Map.of("COUNTY", new BigDecimal("200")));
        assertFalse(p.marginComplete());
        assertNull(p.marginKes());
        assertNull(p.marginPct());
        assertEquals(new BigDecimal("420.00"), p.lines().get(1L).lineMargin(), "known lines still show their margin");
    }
}
