package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.inventory.enums.CloseDayStatus;
import com.dayworks_ltd.loyalty_engine.inventory.enums.CountTrigger;
import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockCountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Staff doing the nightly stock count on WhatsApp (ASANO, merchant 23). */
class WhatsAppStockCountTest {

    private static final String STAFF = "254708613719";
    private static final String STRANGER = "254700000001";
    private static final String SHOP = "23";
    private static final ShoppingListReader.Vertical LIQUOR = ShoppingListReader.Vertical.LIQUOR;

    private WhatsAppCloudClient wa;
    private StockCountService counts;
    private ShoppingListReader reader;
    private WhatsAppStockCount count;
    private ReportRecipientService recipients;
    private CloseDay day;
    private final List<StockCountLine> lines = new ArrayList<>();

    private static StockCountLine line(long id, String code, String name, CountTrigger trigger) {
        return StockCountLine.builder().id(id).closeDayId(1L).merchantId(SHOP).itemCode(code).itemName(name)
                .status(StockCountStatus.PENDING).trigger(trigger).build();
    }

    private static ShoppingListReader.CountLine read(String said, Integer n, String code, String confidence) {
        return new ShoppingListReader.CountLine(said, n, code, confidence, List.of());
    }

    @BeforeEach
    void setUp() {
        wa = mock(WhatsAppCloudClient.class);
        counts = mock(StockCountService.class);
        reader = mock(ShoppingListReader.class);
        recipients = mock(ReportRecipientService.class);
        VoiceTranscriber transcriber = mock(VoiceTranscriber.class);
        InventoryRepository inventory = mock(InventoryRepository.class);
        count = new WhatsAppStockCount(wa, counts, recipients, reader, transcriber, inventory, new MediaArchive());

        when(recipients.isStaffNumber(SHOP, STAFF)).thenReturn(true);
        when(recipients.shopName(SHOP)).thenReturn("ASANO");
        when(reader.isConfigured()).thenReturn(true);
        when(inventory.findByMerchantId(SHOP)).thenReturn(List.of());

        day = CloseDay.builder().id(1L).merchantId(SHOP).status(CloseDayStatus.COUNTING)
                .totalSkusQueued(3).totalSkusCounted(0).build();
        lines.add(line(11, "23-TUSKER", "TUSKER 500ML", CountTrigger.MOVEMENT_BASED));
        lines.add(line(12, "23-KC14", "KENYA CANE 1/4", CountTrigger.MOVEMENT_BASED));
        lines.add(line(13, "23-GILBEYS34", "GILBEYS 3/4", CountTrigger.RISK_SAMPLED));
        when(counts.today()).thenReturn(LocalDate.of(2026, 10, 28));
        when(counts.startCount(eq(SHOP), anyString())).thenReturn(day);
        when(counts.findSession(eq(SHOP), any())).thenAnswer(i -> Optional.of(day));
        when(counts.getLines(day)).thenAnswer(i -> lines);
        when(counts.submitCount(eq(SHOP), anyString(), anyLong(), anyInt())).thenAnswer(i -> {
            StockCountLine l = lines.stream().filter(x -> x.getId().equals(i.getArgument(2))).findFirst().orElseThrow();
            l.setStatus(StockCountStatus.RECONCILED);
            return l;
        });
    }

    private boolean text(String from, String body) {
        return count.handle(from, SHOP, LIQUOR, "text", body, Map.of());
    }

    private boolean tap(String from, String id) {
        return count.handle(from, SHOP, LIQUOR, "interactive", id, Map.of());
    }

    /** Everything sent to the number, texts and button messages, in order. */
    private String sent() {
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeast(0)).sendText(eq(STAFF), texts.capture());
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeast(0)).sendButtons(eq(STAFF), bodies.capture(), anyList());
        List<String> all = new ArrayList<>(texts.getAllValues());
        all.addAll(bodies.getAllValues());
        return String.join("\n---\n", all);
    }

    @Test
    void orderingMessagesAreNotTaken() {
        assertFalse(text(STAFF, "Tusker 2 crates"));
        assertFalse(text(STRANGER, "hello"));
        verifyNoInteractions(counts);
    }

    @Test
    void aNumberThatIsNotStaffCannotCount() {
        assertTrue(text(STRANGER, "count"));
        verify(counts, never()).startCount(anyString(), anyString());
        verify(wa).sendText(eq(STRANGER), contains("only for the shop's staff"));
        // and is not left in count mode
        assertFalse(text(STRANGER, "Tusker 46"));
    }

    @Test
    void startingShowsHowManyToCountWithoutExpectedFigures() {
        assertTrue(text(STAFF, "Count"));
        verify(counts).startCount(SHOP, "WhatsApp 0708613719");
        String body = sent();
        assertTrue(body.contains("3 items to count"), body);
        assertTrue(body.contains("2 sold or restocked today"), body);
    }

    @Test
    void typedCountsAreReadBackAndOnlySavedAfterConfirming() {
        text(STAFF, "count");
        when(reader.readCount(eq("Tusker 46, KC kota 12"), anyList(), eq(LIQUOR))).thenReturn(new ShoppingListReader.CountReading(
                List.of(read("Tusker", 46, "23-TUSKER", "high"), read("KC kota", 12, "23-KC14", "high")), "text"));

        assertTrue(text(STAFF, "Tusker 46, KC kota 12"));
        verify(counts, never()).submitCount(anyString(), anyString(), anyLong(), anyInt());
        String readBack = sent();
        assertTrue(readBack.contains("1. TUSKER 500ML: *46*"), readBack);
        assertTrue(readBack.contains("2. KENYA CANE 1/4: *12*"), readBack);

        assertTrue(tap(STAFF, "SC:SAVE"));
        verify(counts).submitCount(SHOP, "WhatsApp 0708613719", 11L, 46);
        verify(counts).submitCount(SHOP, "WhatsApp 0708613719", 12L, 12);
        assertTrue(sent().contains("✅ Saved 2 counts."));
        assertTrue(sent().contains("1 item still to count"));
    }

    @Test
    void aLineCanBeCorrectedByNumberBeforeSaving() {
        text(STAFF, "count");
        when(reader.readCount(anyString(), anyList(), eq(LIQUOR))).thenReturn(new ShoppingListReader.CountReading(
                List.of(read("Tusker", 46, "23-TUSKER", "high"), read("KC", 12, "23-KC14", "high")), "text"));
        text(STAFF, "Tusker 46 KC 12");
        text(STAFF, "2 = 14");
        text(STAFF, "ok");
        verify(counts).submitCount(SHOP, "WhatsApp 0708613719", 12L, 14);
        verify(counts).submitCount(SHOP, "WhatsApp 0708613719", 11L, 46);
    }

    @Test
    void aShelfPhotoIsAnEstimateEveryLineMarkedToCheck() {
        text(STAFF, "count");
        when(wa.downloadMedia("m1")).thenReturn(new WhatsAppCloudClient.Media(new byte[]{1, 2}, "image/jpeg"));
        when(reader.readCountPhoto(any(), eq("image/jpeg"), isNull(), anyList(), eq(LIQUOR))).thenReturn(
                new ShoppingListReader.CountReading(List.of(read("Tusker bottles", 18, "23-TUSKER", "high")), "shelf"));

        assertTrue(count.handle(STAFF, SHOP, LIQUOR, "image", "", Map.of("image", Map.of("id", "m1"))));
        String body = sent();
        assertTrue(body.contains("TUSKER 500ML: *18* ⚠️ check"), body);
        assertTrue(body.contains("only what's visible"), body);
        verify(counts, never()).submitCount(anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    void unclearAndUnknownLinesAreAskedAboutNotGuessed() {
        text(STAFF, "count");
        when(reader.readCount(anyString(), anyList(), eq(LIQUOR))).thenReturn(new ShoppingListReader.CountReading(List.of(
                new ShoppingListReader.CountLine("KC", 5, null, "low", List.of("23-KC14")),
                read("Heineken", 7, null, "none"),
                read("Gilbeys", null, "23-GILBEYS34", "low")), "text"));
        text(STAFF, "KC 5 heineken 7 gilbeys");
        String body = sent();
        assertTrue(body.contains("\"KC\": which one? KENYA CANE 1/4"), body);
        assertTrue(body.contains("\"Heineken\" isn't on tonight's count list"), body);
        assertTrue(body.contains("GILBEYS 3/4: how many?"), body);
        verify(wa, never()).sendButtons(eq(STAFF), contains("Save these"), anyList());
    }

    @Test
    void aMismatchAsksForARecountWithoutSayingTheSystemFigure() {
        text(STAFF, "count");
        when(reader.readCount(anyString(), anyList(), eq(LIQUOR))).thenReturn(new ShoppingListReader.CountReading(
                List.of(read("Tusker", 40, "23-TUSKER", "high")), "text"));
        doAnswer(i -> {
            lines.get(0).setStatus(StockCountStatus.RECOUNT_REQUESTED);
            lines.get(0).setExpectedQuantity(46);
            return lines.get(0);
        }).when(counts).submitCount(SHOP, "WhatsApp 0708613719", 11L, 40);
        text(STAFF, "Tusker 40");
        tap(STAFF, "SC:SAVE");
        String body = sent();
        assertTrue(body.contains("count these again and send the new number: TUSKER 500ML"), body);
        // ("Tusker 500ml 46" is the typing example in the welcome message)
        assertFalse(body.replace("Tusker 500ml 46", "").contains("46"), "the expected figure must never be shown: " + body);
    }

    @Test
    void doneAsksFirstThenClosesTheCount() {
        text(STAFF, "count");
        text(STAFF, "done");
        verify(counts, never()).finalizeCount(anyString(), anyString(), anyString());
        assertTrue(sent().contains("3 items haven't been counted"));

        day.setTotalSkusCounted(0);
        when(counts.finalizeCount(SHOP, "WhatsApp 0708613719", "Closed on WhatsApp")).thenReturn(day);
        tap(STAFF, "SC:FINISH");
        verify(counts).finalizeCount(SHOP, "WhatsApp 0708613719", "Closed on WhatsApp");
        assertTrue(sent().contains("Tonight's stock count is closed: 0 of 3"));
        // count mode is over: the next message goes to ordering
        assertFalse(text(STAFF, "Tusker 2"));
    }

    @Test
    void tappingAnOrderingButtonLeavesTheCount() {
        text(STAFF, "count");
        assertFalse(tap(STAFF, "BROWSE"));
        assertFalse(text(STAFF, "Tusker 46"));
    }

    @Test
    void stopLeavesTheCountButKeepsWhatWasSaved() {
        text(STAFF, "count");
        assertTrue(text(STAFF, "stop"));
        assertTrue(sent().contains("Counts you saved are kept"));
        assertFalse(text(STAFF, "hello"));
    }

    @Test
    void theListShowsRecountsAndMovedItemsFirst() {
        lines.get(2).setStatus(StockCountStatus.RECOUNT_REQUESTED);
        text(STAFF, "count");
        text(STAFF, "list");
        String body = sent();
        int gilbeys = body.indexOf("• GILBEYS 3/4 🔁 count again");
        int tusker = body.indexOf("• TUSKER 500ML (moved today)");
        assertTrue(gilbeys >= 0 && tusker > gilbeys, body);
    }

    @Test
    void aClosedCountCannotBeStarted() {
        when(counts.startCount(eq(SHOP), anyString())).thenThrow(new IllegalStateException("already been finalized"));
        assertTrue(text(STAFF, "count"));
        assertTrue(sent().contains("already closed"));
    }

    @Test
    void eveningListSendsOnlyMovedItemsWithStartButton() {
        when(wa.sendButtons(anyString(), anyString(), anyList())).thenReturn(new WhatsAppCloudClient.Sent(true, "m", null));

        assertTrue(count.sendCountList(SHOP, "0797770952"));

        verify(wa).sendButtons(eq("254797770952"), argThat(b -> b.contains("1. TUSKER 500ML") && b.contains("2. KENYA CANE 1/4")
                && !b.contains("GILBEYS")), argThat(b -> b.size() == 1 && b.get(0).id().equals("SC:START")));
        verify(recipients, never()).textOne(anyString(), anyString());
    }

    @Test
    void eveningListFallsBackToSmsWhenWhatsAppCannotReach() {
        when(wa.sendButtons(anyString(), anyString(), anyList())).thenReturn(new WhatsAppCloudClient.Sent(false, null, "131047"));

        assertTrue(count.sendCountList(SHOP, "0797770952"));

        verify(recipients).textOne(eq("0797770952"), argThat(s -> s.contains("TUSKER 500ML; KENYA CANE 1/4")
                && s.contains("COUNT") && !s.contains("\n")));
    }

    @Test
    void eveningListSkipsClosedCount() {
        when(counts.startCount(eq(SHOP), anyString())).thenThrow(new IllegalStateException("already been finalized"));
        assertFalse(count.sendCountList(SHOP, "0797770952"));
        verifyNoInteractions(wa);
    }
}
