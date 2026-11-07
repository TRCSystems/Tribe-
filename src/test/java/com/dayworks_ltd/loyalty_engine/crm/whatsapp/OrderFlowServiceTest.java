package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderLinkService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The WhatsApp order form's screens, with the order services mocked. */
@SuppressWarnings("unchecked")
class OrderFlowServiceTest {

    private static final String TOKEN = "AbCdEf2345";

    private ShopOrderService shopOrders;
    private ShopOrderLinkService links;
    private CrmContactService contacts;
    private OrderFlowService flow;

    @BeforeEach
    void setUp() {
        shopOrders = mock(ShopOrderService.class);
        links = mock(ShopOrderLinkService.class);
        contacts = mock(CrmContactService.class);
        flow = new OrderFlowService(shopOrders, links, contacts);
    }

    private static Map<String, Object> openView() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("shopName", "Mama Pima Wines");
        v.put("distributorName", "ASANO");
        v.put("status", "OPEN");
        v.put("catalog", List.of(
                item("23-TUSKER", "Tusker Lager 500ml", 2950, true),
                item("23-GUINNESS", "Guinness 500ml", 3400, false),
                item("23-KC750", "Kenya Cane 750ml", 7200, true)));
        v.put("usual", List.of(
                Map.of("itemCode", "23-GUINNESS", "quantity", 3),   // out of stock → not pre-filled
                Map.of("itemCode", "23-TUSKER", "quantity", 2)));
        return v;
    }

    private static Map<String, Object> item(String code, String name, int price, boolean inStock) {
        return Map.of("itemCode", code, "itemName", name, "price", BigDecimal.valueOf(price), "inStock", inStock);
    }

    private Map<String, Object> call(String action, Map<String, Object> data) {
        Map<String, Object> req = new HashMap<>();
        req.put("action", action);
        req.put("flow_token", TOKEN);
        req.put("data", data);
        return flow.handle(req);
    }

    private static Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @Test
    void pingAnswersActive() {
        assertEquals(Map.of("data", Map.of("status", "active")), call("ping", Map.of()));
    }

    @Test
    void initShowsInStockCatalogueWithUsualPrefilled() {
        when(shopOrders.view(TOKEN, true)).thenReturn(openView());
        Map<String, Object> r = call("INIT", Map.of());

        assertEquals("ORDER", r.get("screen"));
        Map<String, Object> d = data(r);
        assertEquals("Order from ASANO", d.get("heading"));
        List<Map<String, Object>> catalog = (List<Map<String, Object>>) d.get("catalog");
        assertEquals(List.of("23-TUSKER", "23-KC750"), catalog.stream().map(c -> c.get("id")).toList());
        assertEquals("KES 2,950", catalog.get(0).get("description"));
        assertEquals("23-TUSKER", d.get("item_1"));
        assertEquals("2", d.get("qty_1"));
        assertEquals("", d.get("item_2"));
        assertTrue(d.containsKey("item_" + OrderFlowService.ROWS));
    }

    @Test
    void usedFormIsClosedWithItsOrderCode() {
        Map<String, Object> v = new LinkedHashMap<>(openView());
        v.put("status", "USED");
        v.put("order", Map.of("orderCode", "ORD-77"));
        when(shopOrders.view(TOKEN, true)).thenReturn(v);

        Map<String, Object> r = call("INIT", Map.of());
        assertEquals("CLOSED", r.get("screen"));
        assertTrue(data(r).get("message").toString().contains("ORD-77"));
    }

    @Test
    void expiredFormIsClosed() {
        when(shopOrders.view(TOKEN, true)).thenThrow(new IllegalStateException("This order link has expired"));
        Map<String, Object> r = call("INIT", Map.of());
        assertEquals("CLOSED", r.get("screen"));
        assertTrue(data(r).get("message").toString().startsWith("This order link has expired"));
    }

    @Test
    void reviewWithoutQuantityReturnsToFormKeepingWhatWasTyped() {
        when(shopOrders.view(TOKEN, false)).thenReturn(openView());
        Map<String, Object> r = call("data_exchange", Map.of("action", "review",
                "item_1", "23-KC750", "qty_1", "", "extras", "2 Guinness"));

        assertEquals("ORDER", r.get("screen"));
        Map<String, Object> d = data(r);
        assertEquals("Enter a quantity for item 1", d.get("error_message"));
        assertEquals("23-KC750", d.get("item_1"));
        assertEquals("2 Guinness", d.get("extras"));
        verify(shopOrders, never()).view(TOKEN, true); // a re-show is not a new open
    }

    @Test
    void reviewShowsPricedSummaryAndCarriesTheCart() throws Exception {
        when(shopOrders.quote(eq(TOKEN), anyList())).thenReturn(new ShopOrderService.Quote(
                List.of(new ShopOrderService.QuoteLine("23-TUSKER", "Tusker Lager 500ml", 4,
                        BigDecimal.valueOf(2950), BigDecimal.valueOf(11800))),
                BigDecimal.valueOf(11800)));

        Map<String, Object> r = call("data_exchange", Map.of("action", "review",
                "item_1", "23-TUSKER", "qty_1", "4", "item_2", "", "qty_2", "",
                "extras", "2 Guinness 500ml, Chrome vodka"));

        assertEquals("SUMMARY", r.get("screen"));
        String summary = data(r).get("summary").toString();
        assertTrue(summary.contains("4 x Tusker Lager 500ml - KES 11,800"), summary);
        assertTrue(summary.contains("Total: KES 11,800"), summary);
        assertTrue(summary.contains("2 x Guinness 500ml"), summary);
        assertTrue(summary.contains("1 x Chrome vodka"), summary);
        Map<String, Object> cart = new ObjectMapper().readValue(data(r).get("cart").toString(), Map.class);
        assertEquals(List.of(Map.of("c", "23-TUSKER", "q", 4)), cart.get("items"));
    }

    @Test
    void placeOrdersWithTheWhatsAppNumberAndShowsDone() {
        ShopOrderLink link = ShopOrderLink.builder().id(9L).distributorId(23L).shopMerchantId(501L).build();
        when(links.find(TOKEN)).thenReturn(Optional.of(link));
        when(contacts.getOrCreate(23L, 501L)).thenReturn(CrmContact.builder().whatsappNumber("0712345678").build());
        when(shopOrders.placeOrder(eq(TOKEN), anyList(), anyList(), eq("0712345678")))
                .thenReturn(Map.of("orderCode", "ORD-9", "totalKes", BigDecimal.valueOf(11800)));

        Map<String, Object> r = call("data_exchange", Map.of("action", "place",
                "cart", "{\"items\":[{\"c\":\"23-TUSKER\",\"q\":4}],\"extras\":[{\"n\":\"Guinness 500ml\",\"q\":2}]}"));

        assertEquals("DONE", r.get("screen"));
        assertEquals("ORD-9", data(r).get("order_code"));
        assertTrue(data(r).get("message").toString().contains("KES 11,800"));
        verify(shopOrders).placeOrder(eq(TOKEN),
                eq(List.of(new ShopOrderService.ItemQty("23-TUSKER", 4))),
                eq(List.of(new ShopOrderService.Extra("Guinness 500ml", 2))),
                eq("0712345678"));
    }

    @Test
    void placeErrorStaysOnSummary() {
        ShopOrderLink link = ShopOrderLink.builder().id(9L).distributorId(23L).shopMerchantId(501L).build();
        when(links.find(TOKEN)).thenReturn(Optional.of(link));
        when(contacts.getOrCreate(23L, 501L)).thenReturn(CrmContact.builder().build());
        when(shopOrders.placeOrder(any(), anyList(), anyList(), any()))
                .thenThrow(new IllegalArgumentException("Tusker Lager 500ml is out of stock right now"));

        Map<String, Object> r = call("data_exchange", Map.of("action", "place",
                "cart", "{\"items\":[{\"c\":\"23-TUSKER\",\"q\":4}],\"extras\":[]}"));
        assertEquals("SUMMARY", r.get("screen"));
        assertEquals("Tusker Lager 500ml is out of stock right now", data(r).get("error_message"));
    }

    @Test
    void extrasUnderstandLeadingQuantities() {
        assertEquals(List.of(
                        new ShopOrderService.Extra("Guinness 500ml", 2),
                        new ShopOrderService.Extra("Kenya Cane", 12),
                        new ShopOrderService.Extra("Chrome vodka 250ml", 1)),
                OrderFlowService.parseExtras("2 Guinness 500ml\n12x Kenya Cane; Chrome vodka 250ml"));
        assertTrue(OrderFlowService.parseExtras("  ").isEmpty());
    }

    @Test
    void flowJsonHasOneRowPerServiceRowAndMatchingScreens() throws Exception {
        Map<String, Object> json = new ObjectMapper().readValue(
                new ClassPathResource("whatsapp/order-flow.json").getInputStream(), Map.class);
        List<Map<String, Object>> screens = (List<Map<String, Object>>) json.get("screens");
        assertEquals(List.of("ORDER", "SUMMARY", "DONE", "CLOSED"), screens.stream().map(s -> s.get("id")).toList());
        Map<String, Object> orderData = (Map<String, Object>) screens.get(0).get("data");
        assertTrue(orderData.containsKey("item_" + OrderFlowService.ROWS));
        assertFalse(orderData.containsKey("item_" + (OrderFlowService.ROWS + 1)));
    }
}
