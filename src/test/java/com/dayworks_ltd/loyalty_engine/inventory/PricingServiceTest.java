package com.dayworks_ltd.loyalty_engine.inventory;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.PriceChange;
import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.PriceChangeRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.WholesalePriceConfigRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Price changes: validation, the change log, and wholesale prices that keep their history. */
@SuppressWarnings("unchecked")
class PricingServiceTest {

    private InventoryRepository inventory;
    private WholesalePriceConfigRepository wholesale;
    private PriceChangeRepository changes;
    private PricingService service;
    private Inventory item;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryRepository.class);
        wholesale = mock(WholesalePriceConfigRepository.class);
        changes = mock(PriceChangeRepository.class);
        service = new PricingService(inventory, wholesale, changes);

        item = new Inventory();
        item.setId(7L);
        item.setMerchantId("23");
        item.setItemCode("23-TUSKER");
        item.setItemName("Tusker 500ml");
        item.setUnitCost(new BigDecimal("180.00"));
        item.setUnitPrice(new BigDecimal("250.00"));
        when(inventory.findByIdAndMerchantId(7L, "23")).thenReturn(Optional.of(item));
        when(inventory.save(any())).thenAnswer(i -> i.getArgument(0));
        when(wholesale.findLatestPrice(any(), any())).thenReturn(Optional.empty());
    }

    private static WholesalePriceConfig activeRow(long id, String price, LocalDate from) {
        WholesalePriceConfig w = new WholesalePriceConfig();
        w.setId(id);
        w.setItemCode("23-TUSKER");
        w.setMerchantId("23");
        w.setWholesalePrice(new BigDecimal(price));
        w.setEffectiveFrom(from);
        w.setIsActive(true);
        return w;
    }

    private List<PriceChange> loggedChanges() {
        ArgumentCaptor<PriceChange> c = ArgumentCaptor.forClass(PriceChange.class);
        verify(changes, atLeast(0)).save(c.capture());
        return c.getAllValues();
    }

    @Test
    void changingCostAndSellingPriceIsLoggedOldToNew() {
        service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, new BigDecimal("190"), new BigDecimal("260"), null, null)));

        assertEquals(0, new BigDecimal("190.00").compareTo(item.getUnitCost()));
        assertEquals(0, new BigDecimal("260.00").compareTo(item.getUnitPrice()));
        List<PriceChange> log = loggedChanges();
        assertEquals(2, log.size());
        assertEquals(PriceChange.Field.UNIT_COST, log.get(0).getField());
        assertEquals(0, new BigDecimal("180.00").compareTo(log.get(0).getOldValue()));
        assertEquals(0, new BigDecimal("190.00").compareTo(log.get(0).getNewValue()));
        assertEquals(5L, log.get(0).getChangedByUserId());
        assertEquals(PriceChange.Field.UNIT_PRICE, log.get(1).getField());
    }

    @Test
    void sameValueIsNotLogged() {
        service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, new BigDecimal("180"), new BigDecimal("250.00"), null, null)));
        assertTrue(loggedChanges().isEmpty());
    }

    @Test
    void newWholesalePriceClosesThePreviousOneAndKeepsIt() {
        WholesalePriceConfig old = activeRow(3L, "220.00", LocalDate.now().minusDays(30));
        when(wholesale.findByItemCodeAndMerchantIdAndIsActiveTrue("23-TUSKER", "23")).thenReturn(new ArrayList<>(List.of(old)));

        service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, null, null, new BigDecimal("215"), null)));

        assertFalse(old.getIsActive(), "old price closed");
        assertEquals(LocalDate.now(), old.getEffectiveTo());
        ArgumentCaptor<WholesalePriceConfig> saved = ArgumentCaptor.forClass(WholesalePriceConfig.class);
        verify(wholesale).save(saved.capture());
        assertEquals(0, new BigDecimal("215.00").compareTo(saved.getValue().getWholesalePrice()));
        assertTrue(saved.getValue().getIsActive());
        assertEquals(LocalDate.now(), saved.getValue().getEffectiveFrom());
        PriceChange log = loggedChanges().get(0);
        assertEquals(PriceChange.Field.WHOLESALE_PRICE, log.getField());
        assertEquals(0, new BigDecimal("220.00").compareTo(log.getOldValue()));
    }

    @Test
    void firstWholesalePriceHasNoOldValue() {
        when(wholesale.findByItemCodeAndMerchantIdAndIsActiveTrue("23-TUSKER", "23")).thenReturn(new ArrayList<>());
        service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, null, null, new BigDecimal("215"), null)));
        assertNull(loggedChanges().get(0).getOldValue());
        verify(wholesale).save(any(WholesalePriceConfig.class));
    }

    @Test
    void unchangedWholesalePriceDoesNothing() {
        when(wholesale.findByItemCodeAndMerchantIdAndIsActiveTrue("23-TUSKER", "23"))
                .thenReturn(new ArrayList<>(List.of(activeRow(3L, "215.00", LocalDate.now().minusDays(2)))));
        service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, null, null, new BigDecimal("215"), null)));
        verify(wholesale, never()).save(any(WholesalePriceConfig.class));
        assertTrue(loggedChanges().isEmpty());
    }

    @Test
    void clearingWholesaleRemovesItFromOrderingAndLogsIt() {
        WholesalePriceConfig old = activeRow(3L, "220.00", LocalDate.now().minusDays(30));
        when(wholesale.findByItemCodeAndMerchantIdAndIsActiveTrue("23-TUSKER", "23")).thenReturn(new ArrayList<>(List.of(old)));

        service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, null, null, null, true)));

        assertFalse(old.getIsActive());
        verify(wholesale, never()).save(any(WholesalePriceConfig.class));
        assertNull(loggedChanges().get(0).getNewValue());
    }

    @Test
    void invalidAmountsAreRejectedWithTheItemName() {
        IllegalArgumentException neg = assertThrows(IllegalArgumentException.class, () ->
                service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, new BigDecimal("-1"), null, null, null))));
        assertTrue(neg.getMessage().startsWith("Tusker 500ml"));
        assertThrows(IllegalArgumentException.class, () ->
                service.update("23", 5L, List.of(new PricingService.PriceUpdate(7L, null, null, BigDecimal.ZERO, null))));
        assertThrows(IllegalArgumentException.class, () ->
                service.update("23", 5L, List.of(new PricingService.PriceUpdate(99L, BigDecimal.ONE, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> service.update("23", 5L, List.of()));
    }

    @Test
    void listShowsMarginsAndTheNewestActiveWholesalePrice() {
        when(inventory.findByMerchantId("23")).thenReturn(List.of(item));
        when(wholesale.findActiveByMerchant("23")).thenReturn(List.of(
                activeRow(9L, "225.00", LocalDate.now()), activeRow(3L, "220.00", LocalDate.now().minusDays(30))));

        Map<String, Object> row = service.list("23").get(0);
        assertEquals(0, new BigDecimal("225.00").compareTo((BigDecimal) row.get("wholesalePrice")));
        assertEquals(0, new BigDecimal("28.0").compareTo((BigDecimal) row.get("retailMarginPct")));     // (250-180)/250
        assertEquals(0, new BigDecimal("20.0").compareTo((BigDecimal) row.get("wholesaleMarginPct")));  // (225-180)/225
    }
}
