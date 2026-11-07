package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.WholesalePriceConfigRepository;
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
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Liquor shops ordering stock from their distributor (ASANO) on WhatsApp: whatsapp.shop.kind=b2b. */
@SuppressWarnings("unchecked")
class ShopAssistantB2bTest {

    private static final String PHONE = "254708613719";

    private WhatsAppCloudClient wa;
    private ShoppingListReader reader;
    private OrderService orderService;
    private OrderRepository orderRepository;
    private MerchantRepository merchants;
    private CrmContactService contacts;
    private PlaceNamer placeNamer;
    private com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopQueryRepository shopQueries;
    private ShopAssistant assistant;
    private final Map<Long, ShopCart> cartStore = new HashMap<>();
    private final Map<String, Inventory> stock = new LinkedHashMap<>();
    private Merchant asano;
    private Order placed;
    private OrderRequest placedRequest;

    private static Inventory item(long id, String code, String name, int price, int qty) {
        Inventory i = new Inventory();
        i.setId(id);
        i.setMerchantId("23");
        i.setItemCode(code);
        i.setItemName(name);
        i.setUnitPrice(BigDecimal.valueOf(price));
        i.setAvailableStock(qty);
        i.setIsActive(true);
        return i;
    }

    private static WholesalePriceConfig wholesale(String code, int price) {
        WholesalePriceConfig w = new WholesalePriceConfig();
        w.setItemCode(code);
        w.setMerchantId("23");
        w.setWholesalePrice(BigDecimal.valueOf(price));
        w.setIsActive(true);
        return w;
    }

    @BeforeEach
    void setUp() {
        wa = mock(WhatsAppCloudClient.class);
        reader = mock(ShoppingListReader.class);
        orderService = mock(OrderService.class);
        orderRepository = mock(OrderRepository.class);
        merchants = mock(MerchantRepository.class);
        contacts = mock(CrmContactService.class);
        placeNamer = mock(PlaceNamer.class);
        shopQueries = mock(com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopQueryRepository.class);
        when(shopQueries.orderSummaryByShop(23L)).thenReturn(List.of());
        when(merchants.findByDistributor_Id(23L)).thenReturn(List.of());
        ShopCartRepository carts = mock(ShopCartRepository.class);
        InventoryRepository inventory = mock(InventoryRepository.class);
        WholesalePriceConfigRepository wholesaleRepo = mock(WholesalePriceConfigRepository.class);
        VoiceTranscriber transcriber = mock(VoiceTranscriber.class);

        stock.put("23-KIBAO14", item(1, "23-KIBAO14", "KIBAO 1/4", 250, 46));
        stock.put("23-CHROME34", item(2, "23-CHROME34", "CHROME 3/4", 950, 4));
        stock.put("23-SMIRNOFF", item(3, "23-SMIRNOFF", "SMIRNOFF VODKA", 1500, 0));
        when(inventory.findByMerchantId("23")).thenAnswer(i -> new ArrayList<>(stock.values()));
        when(inventory.findByMerchantIdAndItemCode(eq("23"), anyString()))
                .thenAnswer(i -> Optional.ofNullable(stock.get((String) i.getArgument(1))));
        // Chrome has a wholesale price; Smirnoff doesn't (falls back to the shelf price)
        when(wholesaleRepo.findActiveByMerchant("23")).thenReturn(List.of(wholesale("23-KIBAO14", 230), wholesale("23-CHROME34", 900)));

        asano = Merchant.builder().id(23L).businessName("ASANO").businessType("LIQUOR").build();
        when(merchants.findById(23L)).thenReturn(Optional.of(asano));
        when(merchants.findByBusinessPhone(anyString())).thenReturn(List.of());
        when(merchants.save(any(Merchant.class))).thenAnswer(i -> {
            Merchant m = i.getArgument(0);
            m.setId(900L);
            return m;
        });
        when(contacts.findByNumber(anyString())).thenReturn(List.of());
        when(contacts.find(anyLong(), anyLong())).thenReturn(Optional.empty());

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
        when(carts.findByPhoneAndStoreMerchantIdAndStatusOrderByIdDesc(anyString(), anyLong(), any())).thenAnswer(i -> cartStore.values().stream()
                .filter(c -> c.getStatus() == i.getArgument(2))
                .sorted(Comparator.comparing(ShopCart::getId).reversed()).toList());

        when(orderService.createOrder(eq("23"), any(OrderRequest.class), eq(OrderSource.SHOP), isNull(), isNull()))
                .thenAnswer(i -> {
                    placedRequest = i.getArgument(1);
                    Order o = new Order();
                    o.setOrderCode("AS-2001");
                    o.setOrderSource(OrderSource.SHOP);
                    o.setPhoneNumber(placedRequest.getPhoneNumber());
                    o.setStatus(OrderStatus.PENDING);
                    List<OrderItem> items = new ArrayList<>();
                    BigDecimal total = BigDecimal.ZERO;
                    for (var l : placedRequest.getItems()) {
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
        when(orderRepository.findByOrderCode("AS-2001")).thenAnswer(i -> Optional.ofNullable(placed));
        when(orderRepository.findByMerchantIdOrderByOrderDateDesc(anyLong())).thenReturn(List.of());
        when(reader.isConfigured()).thenReturn(true);

        assistant = new ShopAssistant(wa, carts, inventory, merchants, reader, transcriber, placeNamer, orderService, orderRepository,
                wholesaleRepo, contacts, shopQueries, new MediaArchive(), mock(WhatsAppStockCount.class), mock(WhatsAppStockReceipt.class), mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(assistant, "mode", "all");
        ReflectionTestUtils.setField(assistant, "storeName", "ASANO");
        ReflectionTestUtils.setField(assistant, "storeId", "23");
        ReflectionTestUtils.setField(assistant, "kind", "b2b");
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

    private List<WhatsAppCloudClient.Button> lastButtons() {
        ArgumentCaptor<List<WhatsAppCloudClient.Button>> b = ArgumentCaptor.forClass(List.class);
        verify(wa, atLeastOnce()).sendButtons(eq(PHONE), anyString(), b.capture());
        return b.getValue();
    }

    private void orderKibaoAndChrome() {
        when(reader.read(anyString(), anyList(), eq(ShoppingListReader.Vertical.LIQUOR), anyMap()))
                .thenReturn(new ShoppingListReader.Reading(List.of(
                        new ShoppingListReader.Line("kibao quarter 24", 24, "23-KIBAO14", "high", List.of(), null, null),
                        new ShoppingListReader.Line("chrome mzinga 6", 6, "23-CHROME34", "high", List.of(), null, null))));
        say("Kibao quarter 24, Chrome mzinga 6");
    }

    @Test
    void welcomeIsForShopsAndPricesAreWholesale() {
        say("hi");
        assertTrue(lastButtonsBody().contains("Karibu ASANO"));
        assertTrue(lastButtonsBody().contains("Wholesale prices"));

        orderKibaoAndChrome();
        String cart = lastButtonsBody();
        // 24 x 230 + 6 x 900, not the 250 / 950 shelf prices
        assertTrue(cart.contains("24 x KIBAO 1/4 — KES 5,520"), cart);
        assertTrue(cart.contains("6 x CHROME 3/4 — KES 5,400 ⚠️ only 4 in stock"), cart);
        assertTrue(cart.contains("Total: KES 10,920"), cart);
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());
    }

    @Test
    void aNewShopGivesNameContactAndLocationOnceThenTheOrderGoesToAsano() {
        when(placeNamer.name(0.2827, 34.7519)).thenReturn("Kenyatta Avenue, Sheywe ward, Lurambi, Kakamega County");
        orderKibaoAndChrome();
        tap("CONFIRM");
        verify(wa).sendText(eq(PHONE), contains("name of your shop or business"));

        say("Mama Njeri Wines & Spirits");
        assertTrue(lastButtonsBody().contains("contact person for Mama Njeri Wines & Spirits"));
        assertEquals("CONTACT_ME", lastButtons().get(0).id());

        tap("CONTACT_ME");
        verify(wa).sendText(eq(PHONE), contains("Where is Mama Njeri Wines & Spirits?"));
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());

        assistant.handle(PHONE, "Ronald Atsali", "wamid.loc", "location", "",
                Map.of("location", Map.of("latitude", 0.2827, "longitude", 34.7519)));

        // The shop is registered under ASANO and the order is a normal shop order at wholesale prices
        ArgumentCaptor<Merchant> shop = ArgumentCaptor.forClass(Merchant.class);
        verify(merchants).save(shop.capture());
        assertEquals("Mama Njeri Wines & Spirits", shop.getValue().getBusinessName());
        assertEquals("0708613719", shop.getValue().getBusinessPhone());
        assertEquals("LIQUOR", shop.getValue().getBusinessType());
        assertSame(asano, shop.getValue().getDistributor());
        verify(contacts).setContactPerson(23L, 900L, "Ronald Atsali", "0708613719");

        assertEquals("900", placedRequest.getMerchantId());
        assertEquals(List.of(24, 6), placedRequest.getItems().stream().map(l -> l.getQuantity()).toList(),
                "not capped at the 4 Chrome in stock: ASANO sees the shortage when it fulfils");
        assertEquals(new BigDecimal("230"), placedRequest.getItems().get(0).getWholesalePrice());
        assertEquals("Ronald Atsali", placed.getCustomerName());
        assertTrue(placed.getDeliveryAddress().startsWith("Kenyatta Avenue, Sheywe ward"));
        assertTrue(placed.getShopNote().contains("CHROME 3/4: only 4 in stock now"), placed.getShopNote());

        String summary = lastButtonsBody();
        assertTrue(summary.contains("Order AS-2001 sent to ASANO"), summary);
        assertTrue(summary.contains("Shop: Mama Njeri Wines & Spirits") && summary.contains("Contact: Ronald Atsali"), summary);
        assertEquals(List.of("PAY:AS-2001", "PAYLATER"), lastButtons().stream().map(WhatsAppCloudClient.Button::id).toList());
        verify(orderService, never()).collectDeliveryPayment(any(), any());
    }

    @Test
    void aTypedContactAndAddressWorkToo() {
        orderKibaoAndChrome();
        tap("CONFIRM");
        say("Baraka Bar");
        say("Jane Wanjiku");
        say("Shivakala building, Kakamega town");
        assertEquals("Jane Wanjiku", placed.getCustomerName());
        assertEquals("Shivakala building, Kakamega town", placed.getDeliveryAddress());
    }

    @Test
    void aSpokenOrCommandShopNameIsNotSaved() {
        orderKibaoAndChrome();
        tap("CONFIRM");
        say("ok");
        verify(wa).sendText(eq(PHONE), contains("Please type your shop or business name"));
        assertNull(cartStore.values().iterator().next().getShopName());
    }

    @Test
    void aKnownShopSkipsTheQuestionsAndCanReorderItsUsual() {
        CrmContact c = CrmContact.builder().distributorId(23L).shopMerchantId(777L).contactName("Jane").build();
        when(contacts.findByNumber(anyString())).thenReturn(List.of(c));
        when(contacts.find(23L, 777L)).thenReturn(Optional.of(c));
        Merchant shop = Merchant.builder().id(777L).businessName("Baraka Bar").location("Lurambi, Kakamega").distributor(asano).build();
        when(merchants.findById(777L)).thenReturn(Optional.of(shop));
        Order last = new Order();
        last.setDistributor(asano);
        last.setStatus(OrderStatus.FULFILLED);
        last.setItems(List.of(OrderItem.builder().itemCode("23-KIBAO14").itemName("KIBAO 1/4").quantity(12).build()));
        when(orderRepository.findByMerchantIdOrderByOrderDateDesc(777L)).thenReturn(List.of(last));

        say("hi");
        assertTrue(lastButtonsBody().contains("Karibu ASANO Baraka Bar"));
        assertEquals("REORDER", lastButtons().get(0).id());

        tap("REORDER");
        assertTrue(lastButtonsBody().contains("12 x KIBAO 1/4 — KES 2,760"));

        tap("CONFIRM");
        assertEquals("777", placedRequest.getMerchantId());
        assertEquals("Jane", placed.getCustomerName());
        assertEquals("Lurambi, Kakamega", placed.getDeliveryAddress());
        verify(merchants, never()).save(any());
        verify(wa, never()).sendText(eq(PHONE), contains("name of your shop"));
    }

    @Test
    void outOfStockProductsCanStillBeOrdered() {
        when(reader.read(anyString(), anyList(), any(), anyMap())).thenReturn(new ShoppingListReader.Reading(List.of(
                new ShoppingListReader.Line("smirnoff 2", 2, "23-SMIRNOFF", "high", List.of(), null, null))));
        say("smirnoff 2");
        String cart = lastButtonsBody();
        assertTrue(cart.contains("2 x SMIRNOFF VODKA — KES 3,000 ⚠️ out of stock now"), cart);
    }

    @Test
    void payNowSendsTheMpesaPromptForTheShopOrder() {
        orderKibaoAndChrome();
        tap("CONFIRM");
        say("Baraka Bar");
        say("Jane Wanjiku");
        say("Shivakala building, Kakamega town");

        tap("PAY:AS-2001");
        verify(orderService).collectDeliveryPayment("AS-2001", "0708613719");
        verify(wa).sendText(eq(PHONE), contains("enter your M-Pesa PIN to pay KES 10,920"));
    }

    @Test
    void cancelWhileGivingShopDetailsEmptiesTheCart() {
        orderKibaoAndChrome();
        tap("CONFIRM");
        say("cancel");
        assertTrue(lastButtonsBody().contains("your cart is now empty"));
        assertEquals(ShopCart.Status.OPEN, cartStore.values().iterator().next().getStatus());
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());
    }

    private void sendPhoto(String shopNameOnPhoto) {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 9};
        when(wa.downloadMedia("receipt-1")).thenReturn(new WhatsAppCloudClient.Media(jpeg, "image/jpeg"));
        when(reader.readPhoto(eq(jpeg), eq("image/jpeg"), any(), anyList(), eq(ShoppingListReader.Vertical.LIQUOR), anyMap()))
                .thenReturn(new ShoppingListReader.Reading(List.of(
                        new ShoppingListReader.Line("Kibao 1/4", 12, "23-KIBAO14", "high", List.of(), null, null)), shopNameOnPhoto));
        assistant.handle(PHONE, "Ronald Atsali", "wamid.photo", "image", "", Map.of("image", Map.of("id", "receipt-1")));
    }

    @Test
    void aShopNamedOnTheReceiptGetsTheOrderEvenFromAnotherShopsPhone() {
        // This phone is RAY LIQUOR's, but the receipt says Karembo Bar: the order goes on Karembo's account
        CrmContact ray = CrmContact.builder().distributorId(23L).shopMerchantId(24L).contactName("Ray").build();
        when(contacts.findByNumber(anyString())).thenReturn(List.of(ray));
        when(merchants.findById(24L)).thenReturn(Optional.of(Merchant.builder().id(24L).businessName("RAY LIQUOR").location("Khwisero").build()));
        Merchant karembo = Merchant.builder().id(38L).businessName("Karembo bar").location("Lurambi").build();
        when(merchants.findById(38L)).thenReturn(Optional.of(karembo));
        when(shopQueries.orderSummaryByShop(23L)).thenReturn(List.of(new Object[]{24L, 9L, null, null}, new Object[]{38L, 4L, null, null}));
        CrmContact karemboContact = CrmContact.builder().distributorId(23L).shopMerchantId(38L).contactName("Esther").build();
        when(contacts.find(23L, 38L)).thenReturn(Optional.of(karemboContact));

        sendPhoto("KAREMBO BAR & LOUNGE");
        verify(wa).sendText(eq(PHONE), contains("This order is for Karembo bar"));

        tap("CONFIRM");
        assertEquals("38", placedRequest.getMerchantId());
        assertEquals("Esther", placed.getCustomerName());
        assertEquals("Lurambi", placed.getDeliveryAddress());
        verify(merchants, never()).save(any());
    }

    @Test
    void anUnknownShopOnTheReceiptIsConfirmedWithOneTapThenRegistered() {
        sendPhoto("Mwangaza Wines");
        tap("CONFIRM");
        // A name we don't know may be misread: one tap to confirm it, never registered silently
        assertTrue(lastButtonsBody().contains("Your photo says this order is for \"Mwangaza Wines\""));
        assertEquals("SHOPNAME_PHOTO", lastButtons().get(0).id());
        verify(merchants, never()).save(any());

        tap("SHOPNAME_PHOTO");
        tap("CONTACT_ME");
        say("Kakamega town, opposite the bus stage");

        ArgumentCaptor<Merchant> shop = ArgumentCaptor.forClass(Merchant.class);
        verify(merchants).save(shop.capture());
        assertEquals("Mwangaza Wines", shop.getValue().getBusinessName());
        assertEquals("900", placedRequest.getMerchantId());
    }

    @Test
    void aMisreadShopNameIsCorrectedByTypingAnExistingShop() {
        Merchant md = Merchant.builder().id(49L).businessName("MD Wines").location("Kakamega").build();
        when(merchants.findById(49L)).thenReturn(Optional.of(md));
        when(shopQueries.orderSummaryByShop(23L)).thenReturn(List.<Object[]>of(new Object[]{49L, 3L, null, null}));
        when(contacts.find(23L, 49L)).thenReturn(Optional.of(CrmContact.builder().contactName("Moses").build()));

        sendPhoto("Kline");                       // the receipt said MD Wines
        tap("CONFIRM");
        say("md wines");                          // typed: matched to the existing shop, not a new one

        assertEquals("49", placedRequest.getMerchantId());
        assertEquals("Moses", placed.getCustomerName());
        verify(merchants, never()).save(any());
    }

    @Test
    void aPhoneWithItsOwnShopCanSayTheOrderIsForItself() {
        CrmContact ray = CrmContact.builder().distributorId(23L).shopMerchantId(24L).contactName("Ray").build();
        when(contacts.findByNumber(anyString())).thenReturn(List.of(ray));
        when(contacts.find(23L, 24L)).thenReturn(Optional.of(ray));
        when(merchants.findById(24L)).thenReturn(Optional.of(Merchant.builder().id(24L).businessName("RAY LIQUOR").location("Khwisero").build()));

        sendPhoto("Kline");
        tap("CONFIRM");
        assertEquals(List.of("SHOPNAME_PHOTO", "SHOPNAME_MINE"), lastButtons().stream().map(WhatsAppCloudClient.Button::id).toList());
        tap("SHOPNAME_MINE");
        assertEquals("24", placedRequest.getMerchantId());
    }

    @Test
    void afterPickingWhichOneAMisreadQuantityIsCorrectedWithANumber() {
        stock.put("23-KANEX14", item(5, "23-KANEX14", "KANE EXTRA GOLD 1/4", 250, 29));
        stock.put("23-KANEX34", item(6, "23-KANEX34", "KANE EXTRA 3/4", 700, 3));
        when(reader.read(anyString(), anyList(), any(), anyMap())).thenReturn(new ShoppingListReader.Reading(List.of(
                new ShoppingListReader.Line("1 Ken extra", 1, null, "low", List.of("23-KANEX14", "23-KANEX34"), null, null))));
        say("Ken extra");

        // "KANE EXTRA GOLD" and "KANE EXTRA" may be different products, so it asks, showing the quantity it read
        verify(wa).sendList(eq(PHONE), argThat(b -> b.contains("Which \"1 Ken extra\"") && b.contains("(1 unit)")
                && b.contains("Wrong quantity? Send the right number")), eq("Choose"), anyList());

        tap("PICK:23-KANEX14");
        assertTrue(lastButtonsBody().contains("Added 1 x KANE EXTRA GOLD 1/4. Wrong quantity? Send the right number."));

        say("5");
        String cart = lastButtonsBody();
        assertTrue(cart.contains("Changed to 5 x KANE EXTRA GOLD 1/4.") && cart.contains("5 x KANE EXTRA GOLD 1/4 — KES 1,250"), cart);

        say("2");   // the correction is used once: a later number is not another correction
        assertTrue(cartStore.values().iterator().next().getItemsJson().contains("{\"code\":\"23-KANEX14\",\"qty\":5}"));
    }

    @Test
    void aPhotoStartsANewOrderInsteadOfPilingOntoTheCart() {
        orderKibaoAndChrome();                    // e.g. an earlier voice note: 24 Kibao + 6 Chrome in the cart
        sendPhoto(null);                          // the receipt: 12 Kibao

        String cart = lastButtonsBody();
        assertEquals("ADDBACK", lastButtons().get(0).id(), "the set-aside note comes last, with one button");
        assertTrue(cart.contains("Your earlier cart (2 items) is set aside"), cart);
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeastOnce()).sendButtons(eq(PHONE), bodies.capture(), anyList());
        String photoCart = bodies.getAllValues().get(bodies.getAllValues().size() - 2);
        assertTrue(photoCart.contains("12 x KIBAO 1/4") && !photoCart.contains("CHROME"), photoCart);

        tap("ADDBACK");
        String merged = lastButtonsBody();
        assertTrue(merged.contains("36 x KIBAO 1/4") && merged.contains("6 x CHROME 3/4"), merged);
    }

    @Test
    void photosAndVoiceNotesAreKeptForCheckingThenCleanedUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        MediaArchive archive = new MediaArchive();
        ReflectionTestUtils.setField(archive, "dir", dir.toString());
        ReflectionTestUtils.setField(archive, "retentionDays", 14);
        java.nio.file.Path saved = archive.save(new byte[]{1, 2, 3}, "image/jpeg", "photo", PHONE);
        assertNotNull(saved);
        assertTrue(saved.getFileName().toString().endsWith("_3719_photo.jpg"), saved.toString());

        java.nio.file.Path old = archive.save(new byte[]{4}, "audio/ogg; codecs=opus", "voice", PHONE);
        java.nio.file.Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(15))));
        archive.deleteOld();
        assertTrue(java.nio.file.Files.exists(saved));
        assertFalse(java.nio.file.Files.exists(old));

        assertNull(new MediaArchive().save(new byte[]{1}, "image/jpeg", "photo", PHONE), "off unless a folder is set");
    }

    @Test
    void aSizeOnlyDoubtIsSettledFromTheShopsUsualSize() {
        stock.put("23-KIBAO34", item(4, "23-KIBAO34", "KIBAO 3/4", 900, 5));
        Order earlier = new Order();
        earlier.setStatus(OrderStatus.FULFILLED);
        earlier.setMerchant(Merchant.builder().id(77L).build());   // another shop: counts as "most ordered"
        earlier.setItems(List.of(OrderItem.builder().itemCode("23-KIBAO34").quantity(12).build(),
                OrderItem.builder().itemCode("23-KIBAO14").quantity(2).build()));
        when(shopQueries.ordersWithItemsSince(eq(23L), any())).thenReturn(List.of(earlier));
        when(reader.read(anyString(), anyList(), any(), anyMap())).thenReturn(new ShoppingListReader.Reading(List.of(
                new ShoppingListReader.Line("kibao 6", 6, null, "low", List.of("23-KIBAO14", "23-KIBAO34"), null, null),
                new ShoppingListReader.Line("vodka 2", 2, null, "low", List.of("23-CHROME34", "23-SMIRNOFF"), null, null))));

        say("kibao 6, vodka 2");

        verify(wa).sendText(eq(PHONE), contains("6 x KIBAO 3/4 (most ordered size)"));
        // Different products are still a question
        verify(wa).sendList(eq(PHONE), contains("Which \"vodka 2\""), eq("Choose"), anyList());
    }

    @Test
    void sizesOfOneProductAreToldApartFromDifferentProducts() {
        assertTrue(ShopAssistant.sameProduct(List.of("KIBAO 1/4", "KIBAO 1/2", "KIBAO 3/4")));
        assertTrue(ShopAssistant.sameProduct(List.of("CHROME 3/4", "CHROME VODKA 1/4")));
        assertTrue(ShopAssistant.sameProduct(List.of("KENYA CANE LEMON & GINGER", "KENYA CANE LEMON AND GINGER 3/4")));
        assertTrue(ShopAssistant.sameProduct(List.of("VICEROY 1/4", "VICEROY 375ML")));
        assertFalse(ShopAssistant.sameProduct(List.of("CHROME 3/4", "SMIRNOFF VODKA")));
        assertFalse(ShopAssistant.sameProduct(List.of("TUSKER CAN 500", "TUSKER CIDER")));
    }

    @Test
    void theDistributorsOwnNameOnItsReceiptIsIgnored() {
        sendPhoto("ASANO");
        verify(wa, never()).sendText(eq(PHONE), contains("This order is for"));
        tap("CONFIRM");
        verify(wa).sendText(eq(PHONE), contains("name of your shop or business"));
    }

    @Test
    void shopNamesMatchDespiteCaseFillerWordsAndOneMisreadLetter() {
        List<Merchant> shops = List.of(
                Merchant.builder().id(24L).businessName("RAY LIQUOR").build(),
                Merchant.builder().id(37L).businessName("Ray liquor").build(),
                Merchant.builder().id(38L).businessName("Karembo bar").build(),
                Merchant.builder().id(42L).businessName("White house").build());
        assertEquals(24L, ShopAssistant.matchShop("Ray Liquor", shops).getId(), "duplicates: first (most orders) wins");
        assertEquals(38L, ShopAssistant.matchShop("KAREMBO BAR & LOUNGE LTD", shops).getId());
        assertEquals(38L, ShopAssistant.matchShop("Karenbo Bar", shops).getId(), "one misread letter");
        assertEquals(42L, ShopAssistant.matchShop("WHITE HOUSE", shops).getId());
        assertNull(ShopAssistant.matchShop("Baraka Bar", shops));
        assertNull(ShopAssistant.matchShop("Bar", shops), "a generic word alone matches nothing");
    }

    @Test
    void whileTestingEveryOrderAsksWhichShopWithOneTapForThePhonesShops() {
        ReflectionTestUtils.setField(assistant, "askShopEachOrder", true);
        CrmContact ray = CrmContact.builder().distributorId(23L).shopMerchantId(24L).contactName("Ray").build();
        CrmContact md = CrmContact.builder().distributorId(23L).shopMerchantId(49L).contactName("Moses").build();
        when(contacts.findByNumber(anyString())).thenReturn(List.of(ray, md));
        when(contacts.find(23L, 49L)).thenReturn(Optional.of(md));
        when(merchants.findById(24L)).thenReturn(Optional.of(Merchant.builder().id(24L).businessName("RAY LIQUOR").location("Khwisero").build()));
        when(merchants.findById(49L)).thenReturn(Optional.of(Merchant.builder().id(49L).businessName("MD Wines").location("Kakamega").build()));

        orderKibaoAndChrome();
        tap("CONFIRM");
        assertTrue(lastButtonsBody().contains("Which shop is this order for?"));
        assertEquals(List.of("SHOPPICK:24", "SHOPPICK:49"), lastButtons().stream().map(WhatsAppCloudClient.Button::id).toList());
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any());

        tap("SHOPPICK:49");
        assertEquals("49", placedRequest.getMerchantId());
        assertEquals("Moses", placed.getCustomerName());
    }

    @Test
    void shopIsXSetsTheShopOfTheOrderAtAnyTime() {
        ReflectionTestUtils.setField(assistant, "askShopEachOrder", true);
        Merchant md = Merchant.builder().id(49L).businessName("MD Wines").location("Kakamega").build();
        when(merchants.findById(49L)).thenReturn(Optional.of(md));
        when(shopQueries.orderSummaryByShop(23L)).thenReturn(List.<Object[]>of(new Object[]{49L, 3L, null, null}));
        when(contacts.find(23L, 49L)).thenReturn(Optional.of(CrmContact.builder().contactName("Moses").build()));

        orderKibaoAndChrome();
        say("shop is MD wines");
        assertTrue(lastButtonsBody().contains("OK, this order is for MD Wines."));
        verify(reader, times(1)).read(anyString(), anyList(), any(), anyMap());   // not read as a list

        tap("CONFIRM");                           // no shop question: it's set
        assertEquals("49", placedRequest.getMerchantId());

        assertEquals("MD Wines", ShopAssistant.shopNameFrom("Shop: MD Wines."));
        assertEquals("Baraka Bar", ShopAssistant.shopNameFrom("ni ya Baraka Bar"));
        assertEquals("Baraka Bar", ShopAssistant.shopNameFrom("for Baraka Bar"));
        assertNull(ShopAssistant.shopNameFrom("for kibao 6"), "an order line, not a shop");
        assertNull(ShopAssistant.shopNameFrom("kibao robo 24"));
    }

    /** An order this phone placed earlier, still open (pending, unpaid). */
    private void earlierOrder(long cartId, String code, int total) {
        ShopCart c = ShopCart.builder().id(cartId).phone(PHONE).storeMerchantId(23L).status(ShopCart.Status.ORDERED).orderCode(code).build();
        cartStore.put(cartId, c);
        Order o = new Order();
        o.setOrderCode(code);
        o.setStatus(OrderStatus.PENDING);
        o.setTotalAmount(BigDecimal.valueOf(total));
        o.setMerchant(Merchant.builder().id(44L).businessName("BOMBAX").build());
        when(orderRepository.findByOrderCode(code)).thenReturn(Optional.of(o));
    }

    @Test
    void cancelTheLastTwoOrdersListsThemAndCancelsOnYes() {
        earlierOrder(101, "200363", 3600);
        earlierOrder(102, "375531", 3172);

        say("Cancel the last two orders");
        String ask = lastButtonsBody();
        assertTrue(ask.contains("Cancel these 2 orders?") && ask.contains("375531 — BOMBAX, KES 3,172") && ask.contains("200363"), ask);
        assertEquals("CANCELORDERS:375531,200363", lastButtons().get(0).id());
        verify(orderService, never()).cancelOrder(any(), any(), any(), any());

        tap("CANCELORDERS:375531,200363");
        verify(orderService).cancelOrder("375531", "23", null, "Cancelled by the customer on WhatsApp");
        verify(orderService).cancelOrder("200363", "23", null, "Cancelled by the customer on WhatsApp");
        verify(wa).sendText(eq(PHONE), contains("Orders 375531, 200363 are cancelled."));
    }

    @Test
    void cancelOrderByNumberOnlyForThisPhonesOpenOrders() {
        earlierOrder(101, "375531", 3172);
        say("cancel order 375531");
        verify(orderService).cancelOrder("375531", "23", null, "Cancelled by the customer on WhatsApp");

        say("cancel order 999999");
        verify(wa).sendText(eq(PHONE), contains("I can't cancel order 999999"));
        verify(orderService, never()).cancelOrder(eq("999999"), any(), any(), any());
    }

    @Test
    void cancelWordingIsRecognised() {
        assertEquals(List.of("375531"), ShopAssistant.cancelRequest(ShopAssistant.normalize("Cancel order 375531.")).codes());
        assertEquals(List.of("375531", "200363"), ShopAssistant.cancelRequest(ShopAssistant.normalize("cancel orders 375531, 200363")).codes());
        assertEquals(2, ShopAssistant.cancelRequest(ShopAssistant.normalize("Cancel the last two orders")).count());
        assertEquals(3, ShopAssistant.cancelRequest(ShopAssistant.normalize("cancel last 3 orders")).count());
        assertEquals(Integer.MAX_VALUE, ShopAssistant.cancelRequest(ShopAssistant.normalize("cancel all my orders")).count());
        assertNull(ShopAssistant.cancelRequest(ShopAssistant.normalize("cancel")), "a plain cancel keeps its own meaning");
        assertNull(ShopAssistant.cancelRequest(ShopAssistant.normalize("cancel the sugar")));
    }

    @Test
    void aOneOrTwoLetterShopNameOnAPhotoIsIgnored() {
        sendPhoto("x");
        verify(wa, never()).sendText(eq(PHONE), contains("This order is for"));
        tap("CONFIRM");
        verify(wa, never()).sendButtons(eq(PHONE), contains("Your photo says"), anyList());

        var withX = new ShoppingListReader.Reading(List.of(new ShoppingListReader.Line("county", 6, "23-KIBAO14", "high", List.of(), null, null)), "x");
        assertTrue(ShoppingListReader.unsure(withX, Set.of("23-KIBAO14")), "a misread name earns the photo a second look");
    }

    @Test
    void readingHelpersForRealReceiptsAndVoiceNotes() {
        assertEquals("Kc 1/4 5, County 1/4 10", ShoppingListReader.spaceSizes("Kc1/4 5, County 1/4 10"));
        assertEquals("KIBAO kota", VoiceTranscriber.spokenSizes("KIBAO 1/4"));
        assertEquals("KANE EXTRA mzinga", VoiceTranscriber.spokenSizes("KANE EXTRA 3/4"));
        assertEquals("BOND 7 nusu", VoiceTranscriber.spokenSizes("BOND 7 1/2"));

        Set<String> codes = Set.of("23-KIBAO14", "23-CHROME34");
        var sure = new ShoppingListReader.Reading(List.of(new ShoppingListReader.Line("kibao", 6, "23-KIBAO14", "high", List.of(), null, null)));
        assertFalse(ShoppingListReader.unsure(sure, codes));
        var unclear = new ShoppingListReader.Reading(List.of(new ShoppingListReader.Line("ken extra", 1, null, "low", List.of(), null, null)));
        assertTrue(ShoppingListReader.unsure(unclear, codes));
        var madeUpCode = new ShoppingListReader.Reading(List.of(new ShoppingListReader.Line("kc", 5, "23-NOPE", "high", List.of(), null, null)));
        assertTrue(ShoppingListReader.unsure(madeUpCode, codes));

        // The receipt's "M/S MD Kline" is MD Wines (its distinctive word "MD" is there, no other shop has it)
        List<Merchant> shops = List.of(Merchant.builder().id(49L).businessName("MD Wines").build(),
                Merchant.builder().id(24L).businessName("RAY LIQUOR").build());
        assertEquals(49L, ShopAssistant.matchShop("MD Kline", shops).getId());
        assertNull(ShopAssistant.matchShop("Kline", shops));
    }

    @Test
    void theLiquorPromptKnowsBottleSizesAndUsesWholesalePrices() {
        String prompt = ShoppingListReader.systemPrompt(new ArrayList<>(stock.values()), ShoppingListReader.Vertical.LIQUOR,
                Map.of("23-KIBAO14", new BigDecimal("230")));
        assertTrue(prompt.contains("mzinga") && prompt.contains("nusu") && prompt.contains("crate"));
        assertTrue(prompt.contains("23-KIBAO14 | KIBAO 1/4 |  | 230 |"), prompt);
        assertTrue(prompt.contains("23-CHROME34 | CHROME 3/4 |  | 950 |"), "no wholesale price: shelf price");
        assertFalse(prompt.contains("supermarket"));
    }
}
