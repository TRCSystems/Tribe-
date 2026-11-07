package com.dayworks_ltd.loyalty_engine.orders;

import com.dayworks_ltd.loyalty_engine.orders.dto.InsufficientOrderItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The fulfil response tells the portal how many units of each line are missing. */
class InsufficientOrderItemJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shortByIsOrderedMinusInStock() throws Exception {
        JsonNode n = json.valueToTree(new InsufficientOrderItem("UNGA2KG", "Jogoo 2kg", 10, BigDecimal.TEN, 4,
                "Items in stock cannot fully fulfill the order"));
        assertEquals(6, n.get("shortBy").asInt());
        assertEquals(10, n.get("quantity").asInt());
        assertEquals(4, n.get("availableStock").asInt());
    }

    @Test
    void missingOrNegativeStockCountsAsNothingInStock() {
        assertEquals(5, new InsufficientOrderItem("X", "X", 5, null, 0, "Item not found in distributor stock").shortBy());
        assertEquals(5, new InsufficientOrderItem("X", "X", 5, null, -3, null).shortBy());
        assertEquals(0, new InsufficientOrderItem("X", "X", null, null, null, null).shortBy());
    }
}
