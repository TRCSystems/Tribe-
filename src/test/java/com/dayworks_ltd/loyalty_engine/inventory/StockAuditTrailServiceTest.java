package com.dayworks_ltd.loyalty_engine.inventory;

import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryStockAudit;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryStockAuditRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.PriceChangeRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockAuditTrailService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockCountQueueService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The stock audit trail: which column each movement counts in, and the per-product totals. */
@SuppressWarnings("unchecked")
class StockAuditTrailServiceTest {

    private static InventoryStockAudit row(String action, Integer before, Integer after, String by, int minutesAgo) {
        return InventoryStockAudit.builder()
                .inventoryId(1L).itemCode("23-TUSKER").merchantId("23").actionType(action)
                .availableStockBefore(before).availableStockAfter(after)
                .performedBy(by).createdAt(LocalDateTime.now().minusMinutes(minutesAgo)).build();
    }

    private StockAuditTrailService service(List<InventoryStockAudit> rows) {
        InventoryStockAuditRepository audits = mock(InventoryStockAuditRepository.class);
        InventoryTransactionRepository transfers = mock(InventoryTransactionRepository.class);
        PriceChangeRepository prices = mock(PriceChangeRepository.class);
        StockCountQueueService queue = mock(StockCountQueueService.class);
        UserRepository users = mock(UserRepository.class);

        Inventory tusker = new Inventory();
        tusker.setItemCode("23-TUSKER");
        tusker.setItemName("Tusker 500ml");
        tusker.setAvailableStock(37);
        Inventory gin = new Inventory();
        gin.setItemCode("23-GIN");
        gin.setItemName("Gin 250ml");
        gin.setAvailableStock(5);
        Map<String, Inventory> stock = new LinkedHashMap<>();
        stock.put("23-TUSKER", tusker);
        stock.put("23-GIN", gin);

        when(queue.currentStockByItemCode("23")).thenReturn(stock);
        when(audits.findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(eq("23"), any(), any())).thenReturn(rows);
        when(audits.findByMerchantIdAndItemCodeAndCreatedAtBetweenOrderByCreatedAtAscIdAsc(eq("23"), eq("23-TUSKER"), any(), any())).thenReturn(rows);
        when(transfers.findByMerchantIdAndTransactionDateBetweenOrderByTransactionDateDesc(any(), any(), any())).thenReturn(List.of());
        when(transfers.findByMerchantIdAndItemCodeAndTransactionDateBetweenOrderByTransactionDateAsc(any(), any(), any(), any())).thenReturn(List.of());
        when(prices.findByMerchantIdAndItemCodeAndChangedAtBetweenOrderByChangedAtAscIdAsc(any(), any(), any(), any())).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());
        return new StockAuditTrailService(audits, transfers, prices, queue, users);
    }

    private final List<InventoryStockAudit> tuskerDay = List.of(
            row("RESTOCK", 10, 40, "23", 300),                    // +30 added (by the shop login)
            row("SALE", 40, 36, "77", 200),                       // −4 sold by user 77
            row("STOCK_COUNT_ADJUSTMENT", 36, 35, "5", 100),      // −1 shortage written off
            row("MANUAL_CORRECTION", 35, 37, "ADMIN", 50),        // +2 admin
            row("SALE", 37, 37, "77", 10));                       // no movement → ignored

    @Test
    void summaryPutsEachMovementInItsColumnAndListsUnmovedProducts() {
        Map<String, Object> out = service(tuskerDay).summary("23", StockAuditTrailService.period(null, null));
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");

        Map<String, Object> tusker = items.get(0); // most movements first
        assertEquals("Tusker 500ml", tusker.get("itemName"));
        assertEquals(30L, tusker.get("added"));
        assertEquals(-4L, tusker.get("sold"));
        assertEquals(-1L, tusker.get("countCorrections"));
        assertEquals(2L, tusker.get("otherCorrections"));
        assertEquals(27L, tusker.get("netChange"));
        assertEquals(4L, tusker.get("movements"));
        assertEquals(37, tusker.get("currentStock"));

        Map<String, Object> gin = items.get(1);
        assertEquals("Gin 250ml", gin.get("itemName"));
        assertEquals(0L, gin.get("movements"), "products without movement still listed, so any product can be opened");
    }

    @Test
    void itemTrailIsOldestFirstWithPlainWordsAndWho() {
        Map<String, Object> out = service(tuskerDay).itemTrail("23", "23-TUSKER", StockAuditTrailService.period(null, null));
        List<Map<String, Object>> events = (List<Map<String, Object>>) out.get("events");

        assertEquals(4, events.size());
        assertEquals("Stock added", events.get(0).get("description"));
        assertEquals("Shop login", events.get(0).get("by"));
        assertEquals(30, events.get(0).get("change"));
        assertEquals("Sold at the till", events.get(1).get("description"));
        assertEquals("User 77", events.get(1).get("by"));
        assertEquals("Stock count: shortage written off", events.get(2).get("description"));
        assertEquals("COUNT_CORRECTION", events.get(2).get("kind"));
        assertEquals("Admin", events.get(3).get("by"));
        assertEquals(35, events.get(3).get("stockBefore"));
        assertEquals(37, events.get(3).get("stockAfter"));
    }

    @Test
    void periodDefaultsToThirtyDaysAndRejectsBadRanges() {
        StockAuditTrailService.Period p = StockAuditTrailService.period(null, null);
        assertEquals(LocalDate.now().minusDays(29), p.from().toLocalDate());
        assertEquals(LocalDate.now(), p.to().toLocalDate());
        assertThrows(IllegalArgumentException.class, () -> StockAuditTrailService.period(LocalDate.now(), LocalDate.now().minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> StockAuditTrailService.period(LocalDate.now().minusDays(400), LocalDate.now()));
    }

    @Test
    void oldSaleRowsWithoutStockFiguresUseUnitsSold() {
        InventoryStockAudit old = InventoryStockAudit.builder().actionType("SALE").soldStockBefore(3).soldStockAfter(5).build();
        assertEquals(-2, StockAuditTrailService.change(old));
    }
}
