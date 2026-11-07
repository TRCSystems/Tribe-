package com.dayworks_ltd.loyalty_engine.inventory.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain unit test (no Spring, no database) for the stock arithmetic that used to make stock jump. */
class InventoryStockMathTest {

    private static Inventory item(int starting, int added, int sold, int available) {
        Inventory i = new Inventory();
        i.setStartingStock(starting);
        i.setAddedStock(added);
        i.setSoldStock(sold);
        i.setAvailableStock(available);
        return i;
    }

    private static void assertConsistent(Inventory i) {
        assertEquals(i.getAvailableStock(), i.getStartingStock() + i.getAddedStock() - i.getSoldStock(),
                "starting + added - sold must equal available");
    }

    @Test
    void restockAddsExactlyTheQuantityEvenWhenTotalsWereOutOfStep() {
        // The reported bug: shows 10, but totals say 15 (e.g. after a transfer set stock directly)
        Inventory i = item(15, 0, 0, 10);
        i.addStock(20);
        assertEquals(30, i.getAvailableStock(), "10 + 20 must be 30, not 35");
        assertConsistent(i);
    }

    @Test
    void saleAndTransferOutReduceExactly() {
        Inventory i = item(10, 0, 0, 10);
        i.removeStock(3, true);
        assertEquals(7, i.getAvailableStock());
        assertEquals(3, i.getSoldStock());
        i.removeStock(2, false); // issued to a retailer, not a sale
        assertEquals(5, i.getAvailableStock());
        assertEquals(3, i.getSoldStock());
        assertConsistent(i);
    }

    @Test
    void legacyComputeNoLongerMovesStock() {
        Inventory i = item(15, 5, 0, 10);
        i.computeAvailableStock();
        assertEquals(10, i.getAvailableStock(), "editing an item must not change its stock");
        assertConsistent(i);
    }

    @Test
    void restockKeepsWorkingAfterManySteps() {
        Inventory i = item(0, 0, 0, 0);
        i.addStock(10);
        i.removeStock(4, true);
        i.addStock(20);
        i.removeStock(6, false);
        assertEquals(20, i.getAvailableStock());
        assertConsistent(i);
    }
}
