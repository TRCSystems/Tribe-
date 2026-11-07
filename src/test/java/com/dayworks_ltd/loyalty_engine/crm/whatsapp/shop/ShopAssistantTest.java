package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderRequest;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderSource;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A whole WhatsApp grocery order, with WhatsApp, Claude and the order API mocked. */
@SuppressWarnings("unchecked")
class ShopAssistantTest {

    private static final String PHONE = "254708613719";

    private WhatsAppCloudClient wa;
    private ShoppingListReader reader;
    private VoiceTranscriber transcriber;
    private PlaceNamer placeNamer;
    private OrderService orderService;
    private OrderRepository orderRepository;
    private ShopAssistant assistant;
    private final Map<Long, ShopCart> cartStore = new HashMap<>();
    private final Map<String, Inventory> stock = new LinkedHashMap<>();
    private Order placed;

    private static Inventory item(long id, String code, String name, String cat, int price, int qty) {
        Inventory i = new Inventory();
        i.setId(id);
        i.setMerchantId("500");
        i.setItemCode(code);
        i.setItemName(name);
        i.setProductCategory(cat);
        i.setUnitPrice(BigDecimal.valueOf(price));
        i.setAvailableStock(qty);
        i.setIsActive(true);
        return i;
    }

    @BeforeEach
    void setUp() {
        wa = mock(WhatsAppCloudClient.class);
        reader = mock(ShoppingListReader.class);
        orderService = mock(OrderService.class);
        orderRepository = mock(OrderRepository.class);
        ShopCartRepository carts = mock(ShopCartRepository.class);
        InventoryRepository inventory = mock(InventoryRepository.class);
        MerchantRepository merchants = mock(MerchantRepository.class);

        stock.put("500-UNGA2KG", item(1, "500-UNGA2KG", "Jogoo Maize Flour 2kg", "Unga & Flour", 230, 50));
        stock.put("500-SUKARI1KG", item(2, "500-SUKARI1KG", "Mumias Sugar 1kg", "Sugar, Tea & Coffee", 190, 50));
        stock.put("500-KABRAS2KG", item(6, "500-KABRAS2KG", "Kabras Sugar 2kg", "Sugar, Tea & Coffee", 370, 50));
        stock.put("500-MILK500", item(3, "500-MILK500", "Brookside Whole Milk 500ml", "Milk & Dairy", 65, 50));
        stock.put("500-MILK1L", item(4, "500-MILK1L", "Brookside Whole Milk 1L", "Milk & Dairy", 125, 50));
        stock.put("500-COKE", item(5, "500-COKE", "Coca-Cola 500ml", "Drinks", 80, 0));     // out of stock
        when(inventory.findByMerchantId("500")).thenAnswer(i -> new ArrayList<>(stock.values()));
        when(inventory.findByMerchantIdAndItemCode(eq("500"), anyString()))
                .thenAnswer(i -> Optional.ofNullable(stock.get((String) i.getArgument(1))));

        Merchant store = Merchant.builder().id(500L).businessName(FreshMartSeeder.STORE_NAME).build();
        Merchant customers = Merchant.builder().id(501L).businessName(FreshMartSeeder.STORE_NAME + FreshMartSeeder.CUSTOMERS_SUFFIX).build();
        when(merchants.findByBusinessNameContainingIgnoreCase(anyString())).thenReturn(List.of(store, customers));

        when(carts.save(any())).thenAnswer(i -> {
            ShopCart c = i.getArgument(0);
            if (c.getId() == null) c.setId((long) cartStore.size() + 1);
            cartStore.put(c.getId(), c);
            return c;
        });
        when(carts.findFirstByPhoneAndStoreMerchantIdAndStatusInOrderByIdDesc(anyString(), anyLong(), anyCollection()))
                .thenAnswer(i -> cartStore.values().stream()
                        .filter(c -> ((Collection<ShopCart.Status>) i.getArgument(2)).contains(c.getStatus()))
                        .reduce((a, b) -> b));
        when(carts.lockById(anyLong())).thenAnswer(i -> Optional.ofNullable(cartStore.get((Long) i.getArgument(0))));

        when(orderService.createOrder(eq("500"), any(OrderRequest.class), eq(OrderSource.CUSTOMER), isNull(), isNull()))
                .thenAnswer(i -> {
                    OrderRequest req = i.getArgument(1);
                    Order o = new Order();
                    o.setOrderCode("FM-1001");
                    o.setOrderSource(OrderSource.CUSTOMER);
                    o.setPhoneNumber(req.getPhoneNumber());
                    o.setStatus(com.dayworks_ltd.loyalty_engine.common.OrderStatus.PENDING);
                    List<OrderItem> items = new ArrayList<>();
                    BigDecimal total = BigDecimal.ZERO;
                    for (var l : req.getItems()) {
                        BigDecimal line = l.getWholesalePrice().multiply(BigDecimal.valueOf(l.getQuantity()));
                        items.add(OrderItem.builder().itemCode(l.getItemCode()).itemName(l.getItemName())
                                .quantity(l.getQuantity()).wholesalePrice(l.getWholesalePrice()).lineTotal(line).build());
                        total = total.add(line);
                    }
                    o.setItems(items);
                    o.setTotalAmount(total);
                    placed = o;
                    return o;
                });
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.findByOrderCode("FM-1001")).thenAnswer(i -> Optional.ofNullable(placed));
        when(reader.isConfigured()).thenReturn(true);
        transcriber = mock(VoiceTranscriber.class);
        when(transcriber.isConfigured()).thenReturn(true);
        placeNamer = mock(PlaceNamer.class);

        assistant = new ShopAssistant(wa, carts, inventory, merchants, reader, transcriber, placeNamer, orderService, orderRepository,
                mock(com.dayworks_ltd.loyalty_engine.inventory.repositories.WholesalePriceConfigRepository.class),
                mock(com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService.class),
                mock(com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopQueryRepository.class), new MediaArchive(), mock(WhatsAppStockCount.class), mock(WhatsAppStockReceipt.class),
                mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(assistant, "mode", "all");
        ReflectionTestUtils.setField(assistant, "storeName", FreshMartSeeder.STORE_NAME);
    }

    private void say(String text) {
        assistant.handle(PHONE, "Ronald Atsali", "wamid." + UUID.randomUUID(), "text", text, Map.of());
    }

    private void tap(String id) {
        assistant.handle(PHONE, "Ronald Atsali", "wamid." + UUID.randomUUID(), "interactive", id, Map.of());
    }

    private String lastButtonsBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeastOnce()).sendButtons(eq(PHONE), body.capture(), anyList());
        return body.getValue();
    }

    @Test
    void greetingShowsTheThreeWaysToOrder() {
        say("Habari");
        ArgumentCaptor<List<WhatsAppCloudClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
        verify(wa).sendButtons(eq(PHONE), contains("Karibu ASANO Fresh Mart Ronald"), buttons.capture());
        assertEquals(List.of("BROWSE", "LIST", "PHOTO"), buttons.getValue().stream().map(WhatsAppCloudClient.Button::id).toList());
        verify(wa).markReadAndTyping(startsWith("wamid."));
    }

    @Test
    void typedListAddsClearItemsAsksAboutUnclearOnesAndReportsMissing() {
        when(reader.read(anyString(), anyList(), any(), anyMap())).thenReturn(new ShoppingListReader.Reading(List.of(
                new ShoppingListReader.Line("unga 2kg", 2, "500-UNGA2KG", "high", List.of(), null, null),
                new ShoppingListReader.Line("maziwa", 3, null, "low", List.of("500-MILK500", "500-MILK1L"), null, null),
                new ShoppingListReader.Line("coke", 6, "500-COKE", "high", List.of(), null, null),
                new ShoppingListReader.Line("samaki", 1, null, "none", List.of(), null, null),
                new ShoppingListReader.Line("bad code", 1, "999-HALLUCINATED", "high", List.of(), null, null))));

        say("Nipe unga 2kg mbili, maziwa 3, coke 6 na samaki");

        verify(wa).sendText(eq(PHONE), argThat(t -> t.contains("2 x Jogoo Maize Flour 2kg")
                && t.contains("coke (out of stock)") && t.contains("samaki") && t.contains("bad code")));
        ArgumentCaptor<List<WhatsAppCloudClient.Row>> rows = ArgumentCaptor.forClass(List.class);
        verify(wa).sendList(eq(PHONE), contains("\"maziwa\""), eq("Choose"), rows.capture());
        assertEquals(List.of("PICK:500-MILK500", "PICK:500-MILK1L", "SKIPQ"),
                rows.getValue().stream().map(WhatsAppCloudClient.Row::id).toList());

        tap("PICK:500-MILK1L");
        String cart = lastButtonsBody();
        assertTrue(cart.contains("2 x Jogoo Maize Flour 2kg — KES 460"), cart);
        assertTrue(cart.contains("3 x Brookside Whole Milk 1L — KES 375"), cart);
        assertTrue(cart.contains("Total: KES 835"), cart);
    }

    @Test
    void browsingShowsOnlyInStockItemsAndAsksForAQuantity() {
        tap("BROWSE");
        ArgumentCaptor<List<WhatsAppCloudClient.Row>> cats = ArgumentCaptor.forClass(List.class);
        verify(wa).sendList(eq(PHONE), anyString(), eq("Categories"), cats.capture());
        assertEquals(List.of("Unga & Flour", "Sugar, Tea & Coffee", "Milk & Dairy", "Drinks"),
                cats.getValue().stream().map(WhatsAppCloudClient.Row::title).toList());

        tap("CAT:3"); // Drinks: only Coke, which is out of stock
        verify(wa).sendText(eq(PHONE), contains("out of stock"));

        tap("CAT:2");
        ArgumentCaptor<List<WhatsAppCloudClient.Row>> prods = ArgumentCaptor.forClass(List.class);
        verify(wa).sendList(eq(PHONE), startsWith("Milk & Dairy"), eq("Products"), prods.capture());
        assertEquals(2, prods.getValue().size());
        assertEquals("KES 65 · Brookside Whole Milk 500ml", prods.getValue().get(0).description());

        tap("ITEM:500-MILK500");
        say("4");
        assertTrue(lastButtonsBody().contains("4 x Brookside Whole Milk 500ml — KES 260"));
    }

    @Test
    void confirmAsksForAddressThenCreatesOneOrderAndSendsMpesa() throws Exception {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:2");
        tap("CONFIRM");
        verify(wa).sendText(eq(PHONE), contains("Where should we deliver?"));

        say("Milimani estate, house 12, near Kakamega Hospital");

        ArgumentCaptor<OrderRequest> req = ArgumentCaptor.forClass(OrderRequest.class);
        verify(orderService).createOrder(eq("500"), req.capture(), eq(OrderSource.CUSTOMER), isNull(), isNull());
        assertEquals("501", req.getValue().getMerchantId(), "buyer is the store's WhatsApp customers account");
        assertEquals("0708613719", req.getValue().getPhoneNumber());
        assertEquals(0, BigDecimal.valueOf(190).compareTo(req.getValue().getItems().get(0).getWholesalePrice()),
                "price comes from live stock, not from the chat");
        assertEquals("Milimani estate, house 12, near Kakamega Hospital", placed.getDeliveryAddress());
        assertEquals("Ronald Atsali", placed.getCustomerName());
        verify(wa).sendText(eq(PHONE), argThat(t -> t.contains("Order FM-1001 placed") && t.contains("Total: KES 380")));
        verify(orderService).collectDeliveryPayment("FM-1001", "0708613719");
        verify(wa).sendText(eq(PHONE), contains("enter your M-Pesa PIN to pay KES 380"));

        // A second address message (retry / double tap) doesn't create another order
        say("Milimani estate again");
        verify(orderService, times(1)).createOrder(any(), any(), any(), any(), any());
    }

    @Test
    void stockThatRanOutBeforeConfirmingIsTrimmed() {
        tap("ITEM:500-UNGA2KG");
        tap("QTY:500-UNGA2KG:3");
        stock.get("500-UNGA2KG").setAvailableStock(1);
        tap("CONFIRM");
        say("Kakamega town");
        verify(wa).sendText(eq(PHONE), argThat(t -> t.contains("only 1 Jogoo Maize Flour 2kg left")));
        assertEquals(1, placed.getItems().get(0).getQuantity());
    }

    @Test
    void photoOfAListIsReadLikeATypedListAndNothingIsOrderedYet() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        when(wa.downloadMedia("media-77")).thenReturn(new WhatsAppCloudClient.Media(jpeg, "image/jpeg"));
        when(reader.readPhoto(eq(jpeg), eq("image/jpeg"), eq("for the weekend"), anyList(), any(), anyMap()))
                .thenReturn(new ShoppingListReader.Reading(List.of(
                        new ShoppingListReader.Line("sukari", 2, "500-SUKARI1KG", "high", List.of(), null, null),
                        new ShoppingListReader.Line("mlk?", 1, null, "none", List.of(), null, null))));

        assistant.handle(PHONE, "Ronald", "wamid.p", "image", "",
                Map.of("image", Map.of("id", "media-77", "mime_type", "image/jpeg", "caption", "for the weekend")));

        verify(wa).sendText(eq(PHONE), contains("reading your list"));
        verify(wa).sendText(eq(PHONE), argThat(t -> t.contains("2 x Mumias Sugar 1kg") && t.contains("mlk?")));
        assertTrue(lastButtonsBody().contains("Total: KES 380"));
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());
    }

    @Test
    void voiceNoteIsSaidBackThenReadLikeATypedListAndWaitsForConfirm() {
        byte[] ogg = {'O', 'g', 'g', 'S', 1};
        when(wa.downloadMedia("voice-9")).thenReturn(new WhatsAppCloudClient.Media(ogg, "audio/ogg; codecs=opus"));
        when(transcriber.transcribe(eq(ogg), eq("audio/ogg; codecs=opus"), anyList(), any()))
                .thenReturn("Nipe sukari mbili na unga moja");
        when(reader.read(eq("Nipe sukari mbili na unga moja"), anyList(), any(), anyMap()))
                .thenReturn(new ShoppingListReader.Reading(List.of(
                        new ShoppingListReader.Line("sukari mbili", 2, "500-SUKARI1KG", "high", List.of(), null, null),
                        new ShoppingListReader.Line("unga moja", 1, "500-UNGA2KG", "high", List.of(), null, null))));

        assistant.handle(PHONE, "Ronald", "wamid.v", "audio", "",
                Map.of("audio", Map.of("id", "voice-9", "mime_type", "audio/ogg; codecs=opus", "voice", true)));

        verify(wa).sendText(eq(PHONE), contains("I heard: \"Nipe sukari mbili na unga moja\""));
        String cart = lastButtonsBody();
        assertTrue(cart.contains("2 x Mumias Sugar 1kg") && cart.contains("Total: KES 610"), cart);
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());

        tap("CONFIRM");
        say("Milimani, house 12");
        assertEquals(2, placed.getItems().size());
    }

    @Test
    void silentOrUnreadableVoiceNotesGetAFriendlyAnswer() {
        when(wa.downloadMedia("v-silent")).thenReturn(new WhatsAppCloudClient.Media(new byte[]{1}, "audio/ogg"));
        when(transcriber.transcribe(any(), any(), anyList(), any())).thenReturn("  ");
        assistant.handle(PHONE, "Ronald", "wamid.s", "audio", "", Map.of("audio", Map.of("id", "v-silent")));
        assertTrue(lastButtonsBody().contains("couldn't hear that clearly"));

        when(wa.downloadMedia("v-gone")).thenReturn(null);
        assistant.handle(PHONE, "Ronald", "wamid.g", "audio", "", Map.of("audio", Map.of("id", "v-gone")));
        assertTrue(lastButtonsBody().contains("couldn't open that voice note"));

        when(transcriber.isConfigured()).thenReturn(false);
        assistant.handle(PHONE, "Ronald", "wamid.o", "audio", "", Map.of("audio", Map.of("id", "v-any")));
        assertTrue(lastButtonsBody().contains("Voice orders aren't switched on"));
        verify(reader, never()).read(anyString(), anyList(), any(), anyMap());
    }

    /** A voice note whose transcript is {@code heard}. */
    private void speak(String heard) {
        String id = "v-" + UUID.randomUUID();
        byte[] ogg = {'O', 'g', 'g', 'S'};
        when(wa.downloadMedia(id)).thenReturn(new WhatsAppCloudClient.Media(ogg, "audio/ogg"));
        when(transcriber.transcribe(eq(ogg), anyString(), anyList(), any())).thenReturn(heard);
        assistant.handle(PHONE, "Ronald", "wamid." + id, "audio", "", Map.of("audio", Map.of("id", id)));
    }

    @Test
    void voiceNoteIsNotTakenAsTheDeliveryAddress() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        speak("Milimani, house 12, near the hospital.");
        verify(wa).sendText(eq(PHONE), contains("Please type your delivery address"));
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());
    }

    @Test
    void spokenCancelStopsTheUnpaidOrderAndNeverReachesTheListReader() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        say("Kakamega town, near the market");

        speak("Cancel the order.");

        verify(wa).sendText(eq(PHONE), contains("I heard: \"Cancel the order.\""));
        verify(orderService).cancelOrder("FM-1001", "500", null, "Cancelled by the customer on WhatsApp");
        verify(wa).sendText(eq(PHONE), contains("Order FM-1001 is cancelled"));
        verify(reader, never()).read(anyString(), anyList(), any(), anyMap());
    }

    @Test
    void spokenCancelWhileWaitingForTheAddressGoesBackToTheCart() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        speak("Nataka kucancel order yangu");
        assertTrue(lastButtonsBody().contains("Nothing was ordered and your cart is now empty"));
        assertTrue(cartStore.values().stream().allMatch(c -> c.getItemsJson().equals("[]")), "the cart is emptied");
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());
    }

    @Test
    void cancelBeforeOrderingEmptiesTheCart() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        speak("Please cancel my order");
        assertTrue(lastButtonsBody().contains("your cart is now empty"));
        verify(orderService, never()).cancelOrder(any(), any(), any(), any());

        say("cart");
        assertTrue(lastButtonsBody().contains("Your cart is empty"), "nothing left over for the next order");
    }

    @Test
    void cancellingANewCartLeavesTheEarlierUnpaidOrderAndSaysSo() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        say("Kakamega town, near the market");       // FM-1001 placed, not paid
        tap("ITEM:500-UNGA2KG");
        tap("QTY:500-UNGA2KG:2");                    // a new cart

        say("cancel");
        assertTrue(lastButtonsBody().contains("earlier order FM-1001"));
        verify(orderService, never()).cancelOrder(any(), any(), any(), any());

        say("cancel");                              // cart now empty: this one cancels the order
        verify(orderService).cancelOrder("FM-1001", "500", null, "Cancelled by the customer on WhatsApp");
    }

    @Test
    void anAbandonedCartIsEmptiedOnTheNextVisit() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        cartStore.values().forEach(c -> c.setUpdatedAt(java.time.LocalDateTime.now().minusHours(ShopAssistant.CART_EXPIRY_HOURS + 1)));

        say("hi");
        say("cart");
        assertTrue(lastButtonsBody().contains("Your cart is empty"));
    }

    @Test
    void aRecentCartIsKept() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        cartStore.values().forEach(c -> c.setUpdatedAt(java.time.LocalDateTime.now().minusHours(2)));
        say("cart");
        assertTrue(lastButtonsBody().contains("1 x Mumias Sugar 1kg"));
    }

    @Test
    void spokenCommandsWorkLikeTyped() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        speak("Show my cart.");
        speak("Confirm order!");
        verify(wa).sendText(eq(PHONE), contains("Where should we deliver?"));
        verify(reader, never()).read(anyString(), anyList(), any(), anyMap());
    }

    @Test
    void failingCancelGetsAnAnswerInsteadOfSilence() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        say("Kakamega town, near the market");
        doThrow(new RuntimeException("db down")).when(orderService).cancelOrder(any(), any(), any(), any());
        say("Cancel.");
        verify(wa).sendText(eq(PHONE), contains("couldn't cancel order FM-1001 just now"));
    }

    private void shareLocation(Map<String, Object> location) {
        assistant.handle(PHONE, "Ronald", "wamid." + UUID.randomUUID(), "location", "", Map.of("location", location));
    }

    @Test
    void aPinnedLocationGetsItsPlaceNameAndTheMapLink() {
        when(placeNamer.name(0.2827, 34.7519)).thenReturn("Milimani Road, Milimani, Kakamega");
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        shareLocation(Map.of("latitude", 0.2827, "longitude", 34.7519));

        assertEquals("Milimani Road, Milimani, Kakamega · https://maps.google.com/?q=0.2827,34.7519", placed.getDeliveryAddress());
        verify(wa).sendText(eq(PHONE), contains("Deliver to: Milimani Road, Milimani, Kakamega · https://maps.google.com/?q=0.2827,34.7519"));
    }

    @Test
    void aPickedPlaceKeepsWhatsAppsNameAndSkipsTheLookup() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        shareLocation(Map.of("latitude", "0.28", "longitude", "34.75", "name", "Kakamega Mall", "address", "Canon Awori St"));

        assertEquals("Kakamega Mall, Canon Awori St · https://maps.google.com/?q=0.28,34.75", placed.getDeliveryAddress());
        verify(placeNamer, never()).name(anyDouble(), anyDouble());
    }

    @Test
    void aPinWithNoKnownNameStillPlacesTheOrder() {
        when(placeNamer.name(anyDouble(), anyDouble())).thenReturn(null);
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        shareLocation(Map.of("latitude", -1.29, "longitude", 36.82));
        assertEquals("Pinned location · https://maps.google.com/?q=-1.29,36.82", placed.getDeliveryAddress());
    }

    @Test
    void placeNamesAreShortAndReadable() {
        Map<String, Object> body = Map.of("name", "Kakamega Mall", "display_name", "Kakamega Mall, Canon Awori St, Kakamega, 50100, Kenya",
                "address", Map.of("road", "Canon Awori Street", "suburb", "Milimani", "town", "Kakamega", "postcode", "50100", "country", "Kenya"));
        assertEquals("Kakamega Mall, Canon Awori Street, Milimani, Kakamega", PlaceNamer.shortName(body));
        assertEquals("Road A, Town B", PlaceNamer.shortName(Map.of("display_name", "Road A, Town B, 00100, Kenya", "address", Map.of())));
        assertEquals("Kisumu", PlaceNamer.shortName(Map.of("name", "", "address", Map.of("city", "Kisumu"))));
        // What Nominatim really returns for a pin on Kenyatta Avenue, Kakamega
        assertEquals("Kenyatta Avenue, Sheywe ward, Lurambi, Kakamega County", PlaceNamer.shortName(Map.of("name", "Kenyatta Avenue",
                "address", Map.of("road", "Kenyatta Avenue", "city", "Sheywe ward", "county", "Lurambi", "state", "Kakamega County",
                        "postcode", "50100", "country", "Kenya"))));
    }

    @Test
    void onlyAClearShortRequestCountsAsCancel() {
        for (String yes : List.of("Cancel.", "cancel!!", "Please cancel my order", "CANCEL THE ORDER", "nataka kucancel order",
                "ghairi oda", "Sitaki tena", "cancel it please", "Tafadhali cancel order yangu")) {
            assertTrue(ShopAssistant.wantsCancel(ShopAssistant.normalize(yes)), yes);
        }
        for (String no : List.of("cancel the sugar and add milk", "nipe sukari mbili", "sitaki sukari", "unga moja, cancel maziwa",
                "", "order")) {
            assertFalse(ShopAssistant.wantsCancel(ShopAssistant.normalize(no)), no);
        }
    }

    @Test
    void transcriptionHintNamesTheStoresProducts() {
        String hint = VoiceTranscriber.hint(new ArrayList<>(stock.values()));
        assertTrue(hint.contains("Jogoo Maize Flour 2kg") && hint.contains("Kabras Sugar 2kg"), hint);
        assertTrue(hint.length() <= 600);
    }

    @Test
    void unreadablePhotosAndListsGetAFriendlyAnswer() {
        when(wa.downloadMedia(any())).thenReturn(null);
        assistant.handle(PHONE, "Ronald", "wamid.x", "image", "", Map.of("image", Map.of("id", "gone")));
        assertTrue(lastButtonsBody().contains("couldn't open that photo"));

        when(reader.read(anyString(), anyList(), any(), anyMap())).thenThrow(new IllegalStateException("timeout"));
        say("unga mbili na sukari");
        assertTrue(lastButtonsBody().contains("couldn't read that list"));
    }

    @Test
    void commandWordsAreNotTakenAsTheDeliveryAddressAndCancelGoesBack() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        say("Order");
        verify(wa).sendText(eq(PHONE), contains("I still need your delivery address"));
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());

        say("cancel");
        assertTrue(lastButtonsBody().contains("Nothing was ordered and your cart is now empty"));
        assertTrue(cartStore.values().stream().allMatch(c -> c.getItemsJson().equals("[]")), "the cart is emptied");
        verify(orderService, never()).cancelOrder(any(), any(), any(), any());
    }

    @Test
    void cancelStopsTheLatestUnpaidOrder() {
        tap("ITEM:500-SUKARI1KG");
        tap("QTY:500-SUKARI1KG:1");
        tap("CONFIRM");
        say("Kakamega town, near the market");
        say("Cancel the order");
        verify(orderService).cancelOrder("FM-1001", "500", null, "Cancelled by the customer on WhatsApp");
        verify(wa).sendText(eq(PHONE), contains("Order FM-1001 is cancelled"));
    }

    @Test
    void aTotalWeightBecomesTheRightNumberOfPacksForWhicheverProductIsPicked() {
        when(reader.read(anyString(), anyList(), any(), anyMap())).thenReturn(new ShoppingListReader.Reading(List.of(
                new ShoppingListReader.Line("sugar 3kg", 3, null, "low", List.of("500-SUKARI1KG", "500-KABRAS2KG"), 3.0, "kg"))));
        say("sugar 3kg");
        verify(wa).sendList(eq(PHONE), contains("(3 kg in total)"), eq("Choose"), anyList());

        tap("PICK:500-KABRAS2KG");
        assertTrue(lastButtonsBody().contains("2 x Kabras Sugar 2kg"), "3kg of 2kg packs rounds to 2 packs, not 3");
    }

    @Test
    void aClearMatchWithATotalUsesThePackSize() {
        when(reader.read(anyString(), anyList(), any(), anyMap())).thenReturn(new ShoppingListReader.Reading(List.of(
                new ShoppingListReader.Line("mumias 3kg", 1, "500-SUKARI1KG", "high", List.of(), 3.0, "kg"),
                new ShoppingListReader.Line("unga 2kg mbili", 2, "500-UNGA2KG", "high", List.of(), null, null))));
        say("mumias 3kg, unga 2kg mbili");
        String cart = lastButtonsBody();
        assertTrue(cart.contains("3 x Mumias Sugar 1kg"), cart);
        assertTrue(cart.contains("2 x Jogoo Maize Flour 2kg"), cart);
    }

    @Test
    void packSizesAreReadFromProductNames() {
        assertEquals(3, ShopAssistant.packsFor(stock.get("500-SUKARI1KG"), 3.0, "kg", 1));
        assertEquals(1, ShopAssistant.packsFor(stock.get("500-MILK1L"), 1000.0, "ml", 5));
        assertEquals(4, ShopAssistant.packsFor(stock.get("500-MILK500"), 2.0, "l", 1));
        assertEquals(7, ShopAssistant.packsFor(stock.get("500-COKE"), null, null, 7), "no total: keep the count");
        assertEquals(2, ShopAssistant.packsFor(stock.get("500-SUKARI1KG"), 2.0, "l", 2), "litres of sugar make no sense: keep the count");
    }

    @Test
    void quantitiesInWordsAndPhoneFormats() {
        assertEquals(2, ShopAssistant.quantity("mbili"));
        assertEquals(5, ShopAssistant.quantity(" 5 "));
        assertNull(ShopAssistant.quantity("0"));
        assertNull(ShopAssistant.quantity("unga"));
        assertEquals("0708613719", ShopAssistant.localPhone("254708613719"));
    }

    @Test
    void freshMartCatalogueFitsWhatsAppAndHasUniqueNames() {
        var names = FreshMartSeeder.CATALOG.stream().map(p -> p.name().toUpperCase().replaceAll("[^A-Z0-9]", "")).toList();
        assertEquals(names.size(), new HashSet<>(names).size(), "item codes must be unique");
        assertTrue(FreshMartSeeder.CATALOG.size() >= 75, "diverse catalogue");
        Set<String> cats = FreshMartSeeder.CATALOG.stream().map(p -> p.category()).collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(cats.size() <= 10, "WhatsApp lists show at most 10 categories");
        cats.forEach(c -> assertTrue(c.length() <= 24, c + " is too long for a list row"));
    }
}
