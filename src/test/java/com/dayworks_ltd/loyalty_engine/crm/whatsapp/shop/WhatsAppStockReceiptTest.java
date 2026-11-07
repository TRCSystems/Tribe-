package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockReceipt;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.StockReceiptRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockAuditRecorder;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adding stock from a supplier receipt photo on WhatsApp, held until the approver says yes. */
class WhatsAppStockReceiptTest {

    static final String SHOP = "23";
    static final String STAFF = "254797770952";
    static final String APPROVER = "254708613719";
    static final ShoppingListReader.Vertical LIQUOR = ShoppingListReader.Vertical.LIQUOR;

    WhatsAppCloudClient wa;
    ShoppingListReader reader;
    InventoryRepository inventory;
    StockAuditRecorder audit;
    WhatsAppStockReceipt receipt;
    final Map<Long, StockReceipt> saved = new HashMap<>();
    final Map<String, Inventory> stock = new LinkedHashMap<>();

    static Inventory item(String code, String name, int available, String cost) {
        Inventory i = new Inventory();
        i.setId((long) code.hashCode());
        i.setMerchantId(SHOP);
        i.setItemCode(code);
        i.setItemName(name);
        i.setAvailableStock(available);
        i.setUnitCost(new BigDecimal(cost));
        i.setUnitPrice(new BigDecimal("300"));
        i.setRecordDate(LocalDate.now());
        return i;
    }

    static ShoppingListReader.ReceiptLine line(String written, String packs, Integer units, Double cost, String code,
                                               String confidence, List<String> alternatives, String newName) {
        return new ShoppingListReader.ReceiptLine(written, packs, units, cost, null, code, confidence, alternatives, newName);
    }

    @BeforeEach
    void setUp() {
        wa = mock(WhatsAppCloudClient.class);
        when(wa.sendButtons(anyString(), anyString(), anyList())).thenReturn(new WhatsAppCloudClient.Sent(true, "m", null));
        when(wa.sendText(anyString(), anyString())).thenReturn(new WhatsAppCloudClient.Sent(true, "m", null));
        when(wa.downloadMedia("img1")).thenReturn(new WhatsAppCloudClient.Media(new byte[]{1, 2, 3}, "image/jpeg"));

        reader = mock(ShoppingListReader.class);
        when(reader.isConfigured()).thenReturn(true);

        stock.put("23-TUSKER500ML", item("23-TUSKER500ML", "TUSKER 500ML", 10, "200"));
        stock.put("23-KANEEXTRA1/4", item("23-KANEEXTRA1/4", "KANE EXTRA 1/4", 5, "150"));
        stock.put("23-KANEEXTRA1/2", item("23-KANEEXTRA1/2", "KANE EXTRA 1/2", 5, "280"));
        inventory = mock(InventoryRepository.class);
        when(inventory.findByMerchantId(SHOP)).thenAnswer(i -> new ArrayList<>(stock.values()));
        when(inventory.findByMerchantIdAndItemCode(eq(SHOP), anyString())).thenAnswer(i -> Optional.ofNullable(stock.get((String) i.getArgument(1))));
        when(inventory.findByMerchantIdAndItemName(eq(SHOP), anyString())).thenAnswer(i -> stock.values().stream()
                .filter(x -> x.getItemName().equals(i.getArgument(1))).findFirst());
        when(inventory.save(any())).thenAnswer(i -> {
            Inventory x = i.getArgument(0);
            if (x.getId() == null) x.setId(999L);
            stock.put(x.getItemCode(), x);
            return x;
        });

        StockReceiptRepository repo = mock(StockReceiptRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            StockReceipt r = i.getArgument(0);
            if (r.getId() == null) r.setId((long) saved.size() + 1);
            saved.put(r.getId(), r);
            return r;
        });
        when(repo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(saved.get((Long) i.getArgument(0))));
        when(repo.findByApproverPhoneAndStatusOrderByCreatedAtAsc(anyString(), any())).thenAnswer(i -> saved.values().stream()
                .filter(r -> r.getApproverPhone().equals(i.getArgument(0)) && r.getStatus() == i.getArgument(1)).toList());
        when(repo.findByMerchantIdAndReceiptNumberAndStatusIn(anyString(), anyString(), any())).thenAnswer(i -> saved.values().stream()
                .filter(r -> i.getArgument(1).equals(r.getReceiptNumber()) && ((Collection<?>) i.getArgument(2)).contains(r.getStatus())).toList());

        ReportRecipientService recipients = mock(ReportRecipientService.class);
        when(recipients.isStaffNumber(SHOP, STAFF)).thenReturn(true);
        when(recipients.approverFor(SHOP)).thenReturn("0708613719");
        when(recipients.shopName(SHOP)).thenReturn("ASANO");

        audit = mock(StockAuditRecorder.class);
        StockReceiptService service = new StockReceiptService(repo, inventory, audit, recipients);
        receipt = new WhatsAppStockReceipt(wa, service, recipients, reader, inventory, new MediaArchive(), mock(WhatsAppProperties.class));
    }

    private boolean text(String from, String body) {
        return receipt.handle(from, SHOP, LIQUOR, "text", body, Map.of());
    }

    private boolean photo(String from, String caption) {
        Map<String, Object> image = new HashMap<>(Map.of("id", "img1"));
        if (caption != null) image.put("caption", caption);
        return receipt.handle(from, SHOP, LIQUOR, "image", "", Map.of("image", image));
    }

    private void readsAs(ShoppingListReader.ReceiptLine... lines) {
        when(reader.readReceipt(any(), anyString(), any(), anyList(), eq(LIQUOR))).thenReturn(new ShoppingListReader.ReceiptReading(
                true, List.of(lines), "MD WINES", "4521", "2026-10-30", null));
    }

    private String lastTo(String to) {
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeast(0)).sendText(eq(to), texts.capture());
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeast(0)).sendButtons(eq(to), bodies.capture(), anyList());
        List<String> all = new ArrayList<>(texts.getAllValues());
        all.addAll(bodies.getAllValues());
        return String.join("\n---\n", all);
    }

    private void readStandardReceipt() {
        readsAs(line("Tusker 500ml", "2 ctn", 48, 210.0, "23-TUSKER500ML", "high", List.of(), null),
                line("Chrome Gin 250ml", "1 ctn", 24, 180.0, null, "none", List.of(), "CHROME GIN 1/4"),
                line("Kane Extra", "12 btl", 12, 160.0, null, "low", List.of("23-KANEEXTRA1/4", "23-KANEEXTRA1/2"), null));
        assertTrue(text(STAFF, "stock"));
        assertTrue(photo(STAFF, null));
    }

    @Test
    void receiptIsReadBackAndNeedsUnsureLinesFixedBeforeSending() {
        readStandardReceipt();

        String sent = lastTo(STAFF);
        assertTrue(sent.contains("MD WINES") && sent.contains("No. 4521"));
        assertTrue(sent.contains("1. TUSKER 500ML +48 (2 ctn) @ KES 210"));
        assertTrue(sent.contains("2. 🆕 CHROME GIN 1/4 +24"));
        assertTrue(sent.contains("3. ❓ Kane Extra +12") && sent.contains("KANE EXTRA 1/4 / KANE EXTRA 1/2"));

        text(STAFF, "ok");
        assertTrue(saved.isEmpty());
        assertTrue(lastTo(STAFF).contains("Lines 3 still need fixing"));
    }

    @Test
    void fixedReceiptGoesToApproverAndStockOnlyMovesAfterApproval() {
        readStandardReceipt();
        text(STAFF, "3 is kane extra 1/4");
        text(STAFF, "1 cost 1,205.50");
        assertTrue(lastTo(STAFF).contains("3. KANE EXTRA 1/4 +12 @ KES 160"));
        assertTrue(lastTo(STAFF).contains("TUSKER 500ML +48 (2 ctn) @ KES 1,205.50"));

        receipt.handle(STAFF, SHOP, LIQUOR, "interactive", "RC:SEND", Map.of());

        assertEquals(StockReceipt.Status.PENDING, saved.get(1L).getStatus());
        assertEquals(10, stock.get("23-TUSKER500ML").getAvailableStock());
        verify(wa).sendButtons(eq(APPROVER), contains("CHROME GIN 1/4 +24"), argThat(b ->
                b.get(0).id().equals("RS:OK:1") && b.get(1).id().equals("RS:NO:1")));
        assertTrue(lastTo(STAFF).contains("Sent to 0708613719 for approval"));

        assertTrue(receipt.handleApproval(APPROVER, "interactive", "RS:OK:1"));

        assertEquals(StockReceipt.Status.APPROVED, saved.get(1L).getStatus());
        Inventory tusker = stock.get("23-TUSKER500ML");
        assertEquals(58, tusker.getAvailableStock());
        assertEquals(new BigDecimal("1205.50"), tusker.getUnitCost());
        assertEquals("MD WINES", tusker.getSupplierName());
        assertEquals(17, stock.get("23-KANEEXTRA1/4").getAvailableStock());
        Inventory gin = stock.get("23-CHROMEGIN1/4");
        assertNotNull(gin, "new product created");
        assertEquals(24, gin.getAvailableStock());
        assertNull(gin.getUnitPrice());
        assertEquals(new BigDecimal("180.00"), gin.getUnitCost());
        verify(audit).record(eq(tusker), eq("RESTOCK"), eq(10), eq(58), eq("Receipt 4521"), anyString(), anyString());
        assertTrue(lastTo(APPROVER).contains("Stock added: 84 units on 3 products"));
        assertTrue(lastTo(APPROVER).contains("set a selling price in Tribe before selling them: CHROME GIN 1/4"));
        assertTrue(lastTo(STAFF).contains("0708613719 approved your receipt No. 4521"));

        // A second tap does nothing
        receipt.handleApproval(APPROVER, "interactive", "RS:OK:1");
        assertEquals(58, stock.get("23-TUSKER500ML").getAvailableStock());
    }

    @Test
    void dontAddLeavesStockAlone() {
        readStandardReceipt();
        text(STAFF, "remove 3");
        text(STAFF, "send");
        receipt.handleApproval(APPROVER, "interactive", "RS:NO:1");

        assertEquals(StockReceipt.Status.REJECTED, saved.get(1L).getStatus());
        assertEquals(10, stock.get("23-TUSKER500ML").getAvailableStock());
        assertFalse(stock.containsKey("23-CHROMEGIN1/4"));
        assertTrue(lastTo(STAFF).contains("did not approve"));
    }

    @Test
    void onlyTheApproverCanApprove() {
        readStandardReceipt();
        text(STAFF, "remove 3");
        text(STAFF, "send");

        receipt.handleApproval(STAFF, "interactive", "RS:OK:1");

        assertEquals(StockReceipt.Status.PENDING, saved.get(1L).getStatus());
        assertEquals(10, stock.get("23-TUSKER500ML").getAvailableStock());
    }

    @Test
    void sameReceiptNumberAgainIsFlagged() {
        readStandardReceipt();
        text(STAFF, "remove 3");
        text(STAFF, "send");

        readStandardReceipt();
        assertTrue(lastTo(STAFF).contains("Receipt No. 4521 was already sent"));
    }

    @Test
    void customersCaptionedReceiptStaysAnOrder() {
        assertFalse(photo("254711111111", "receipt"));
        verifyNoInteractions(reader);
    }

    @Test
    void staffCaptionedPhotoGoesStraightToReading() {
        readsAs(line("Tusker 500ml", "2 ctn", 48, 210.0, "23-TUSKER500ML", "high", List.of(), null));
        assertTrue(photo(STAFF, "stock from MD"));
        assertTrue(lastTo(STAFF).contains("TUSKER 500ML +48"));
    }

    @Test
    void ordinaryMessagesAndCountWordsPassThrough() {
        assertFalse(text(STAFF, "Tusker 500ml 2"));
        assertFalse(photo(STAFF, null));
        text(STAFF, "stock");
        assertFalse(text(STAFF, "count"), "starting the count leaves stock-in");
        assertFalse(text(STAFF, "hello"));
    }
}
