package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopQueryRepository;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.WholesalePriceConfigRepository;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderItemRequest;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderRequest;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderSource;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WhatsApp shop assistant for a store (the demo: ASANO Fresh Mart). A customer can browse categories,
 * type a shopping list (read by Claude, then checked here against live prices and stock), confirm,
 * give a delivery address and pay with M-Pesa. It reuses the existing order API: the store is the
 * seller, the store's "WhatsApp customers" account the buyer, and the order source is CUSTOMER.
 *
 * Rules: a draft is not an order — nothing is created until the customer confirms; prices and stock are
 * rechecked right before the order is created; the confirm step locks the cart so a double tap or a
 * webhook retry can't create a second order; unclear items are asked about, never guessed.
 *
 * With {@code whatsapp.shop.kind=b2b} the same conversation serves shops buying stock from a distributor
 * (the liquor demo: ASANO): wholesale prices, no cap at current stock (the distributor fulfils what it has),
 * shop name + contact person + location asked once before the first order, the order placed as a normal
 * SHOP order in the distributor's order book, pay on delivery with an optional M-Pesa button.
 */
@Slf4j
@Service
public class ShopAssistant {

    private static final int PAGE = 9;            // WhatsApp lists hold 10 rows: 9 products + "More"
    private static final int MAX_QTY = 100;
    private static final long PAYMENT_WAIT_MINUTES = 4;
    /** A cart nobody has touched for this long is emptied on the customer's next message. */
    static final long CART_EXPIRY_HOURS = 24;
    private static final Set<String> GREETINGS = Set.of("hi", "hello", "hey", "habari", "mambo", "niaje", "sasa",
            "menu", "start", "jambo", "hujambo", "karibu", "good morning", "good afternoon", "good evening", "hi there",
            "habari yako", "sasa niaje");
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("moja", 1), Map.entry("mbili", 2), Map.entry("tatu", 3), Map.entry("nne", 4), Map.entry("tano", 5),
            Map.entry("sita", 6), Map.entry("saba", 7), Map.entry("nane", 8), Map.entry("tisa", 9), Map.entry("kumi", 10),
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10));
    private static final Pattern NUMBER = Pattern.compile("^\\s*(\\d{1,3})\\s*$");
    private static final Set<String> CANCEL_WORDS = Set.of("cancel", "cancel order", "cancel the order", "cancel my order",
            "futa order", "futa oda", "sitaki", "sitaki tena", "ghairi", "ghairi order");
    /** Words that are never a delivery address (people send them while we wait for one). */
    private static final Set<String> COMMAND_WORDS = Set.of("order", "orders", "oda", "cart", "kikapu", "basket", "menu",
            "browse", "list", "photo", "help", "msaada", "yes", "no", "ndio", "hapana", "ok", "okay", "sawa", "confirm",
            "pay", "lipa", "done", "thanks", "asante", "clear", "futa");

    private final WhatsAppCloudClient wa;
    private final ShopCartRepository carts;
    private final InventoryRepository inventoryRepository;
    private final MerchantRepository merchantRepository;
    private final ShoppingListReader reader;
    private final VoiceTranscriber transcriber;
    private final PlaceNamer placeNamer;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final WholesalePriceConfigRepository wholesaleRepository;
    private final CrmContactService contacts;
    private final CrmShopQueryRepository shopQueries;
    private final MediaArchive mediaArchive;
    private final WhatsAppStockCount stockCount;
    private final WhatsAppStockReceipt stockReceipt;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    /** off = never; unknown = numbers not linked to a shop in the CRM; all = every chat (demo). */
    @Value("${whatsapp.shop.mode:unknown}")
    private String mode;

    @Value("${whatsapp.shop.store-name:" + FreshMartSeeder.STORE_NAME + "}")
    private String storeName;

    /** Pick the store by merchant id instead of by name (e.g. 23 = ASANO). */
    @Value("${whatsapp.shop.store-id:}")
    private String storeId;

    /** consumer = people buying for home (Fresh Mart); b2b = shops buying stock from a distributor. */
    @Value("${whatsapp.shop.kind:consumer}")
    private String kind;

    /**
     * B2B testing with one phone for many shops: ask which shop every order is for (one tap for shops this phone
     * has ordered for) instead of assuming the phone's own shop.
     */
    @Value("${whatsapp.shop.ask-shop-each-order:false}")
    private boolean askShopEachOrder;

    /** Orders waiting for M-Pesa: orderCode → when the prompt was sent. */
    private final Map<String, LocalDateTime> awaitingPayment = new ConcurrentHashMap<>();

    public ShopAssistant(WhatsAppCloudClient wa, ShopCartRepository carts, InventoryRepository inventoryRepository,
                         MerchantRepository merchantRepository, ShoppingListReader reader, VoiceTranscriber transcriber, PlaceNamer placeNamer,
                         OrderService orderService, OrderRepository orderRepository,
                         WholesalePriceConfigRepository wholesaleRepository, CrmContactService contacts,
                         CrmShopQueryRepository shopQueries, MediaArchive mediaArchive,
                         WhatsAppStockCount stockCount, WhatsAppStockReceipt stockReceipt,
                         PlatformTransactionManager transactionManager) {
        this.wa = wa;
        this.carts = carts;
        this.inventoryRepository = inventoryRepository;
        this.merchantRepository = merchantRepository;
        this.reader = reader;
        this.transcriber = transcriber;
        this.placeNamer = placeNamer;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.wholesaleRepository = wholesaleRepository;
        this.contacts = contacts;
        this.shopQueries = shopQueries;
        this.mediaArchive = mediaArchive;
        this.stockCount = stockCount;
        this.stockReceipt = stockReceipt;
        this.tx = new TransactionTemplate(transactionManager);
    }

    record Item(String code, int qty) {}

    /** total/unit are set when the customer asked for an amount ("sugar 3kg"): packs then depend on the product picked. */
    record Question(String requested, int qty, List<String> options, Double total, String unit) {}

    // ───────────────────────────── routing ─────────────────────────────

    /** Whether this chat goes to the shop assistant (instead of the distributor ordering flow). */
    public boolean handles(boolean senderIsKnownShop) {
        if ("off".equalsIgnoreCase(mode) || store().isEmpty()) return false;
        return "all".equalsIgnoreCase(mode) || !senderIsKnownShop;
    }

    public void handle(String from, String profileName, String messageId, String type, String content,
                       Map<String, Object> message) {
        Merchant store = store().orElse(null);
        if (store == null) return;
        wa.markReadAndTyping(messageId);
        ShopCart cart = openCart(from, store.getId(), profileName);
        String text = content == null ? "" : content.trim();

        try {
            // Staff adding stock from a supplier receipt, or doing tonight's stock count, talk to those, not to ordering
            if (stockReceipt.handle(from, String.valueOf(store.getId()), vertical(store), type, text, message)) return;
            if (stockCount.handle(from, String.valueOf(store.getId()), vertical(store), type, text, message)) return;
            if ("location".equals(type)) {
                if (cart.getStatus() == ShopCart.Status.AWAITING_ADDRESS) deliverTo(from, cart, store, locationText(message));
                else wa.sendText(from, "Thanks for sharing your location. Add items to your cart first, then confirm the order.");
                return;
            }
            if ("image".equals(type)) {
                readPhoto(from, store, cart, message);
                return;
            }
            if ("audio".equals(type)) {
                readVoice(from, store, cart, message);
                return;
            }
            if (!"text".equals(type) && !"interactive".equals(type) && !"button".equals(type)) {
                welcome(from, store, cart, "I can read text, photos of lists and voice notes here.");
                return;
            }
            if (command(from, store, cart, text)) return;
            route(from, store, cart, text, false);
        } catch (Exception e) {
            log.error("Shop assistant failed for {}: {}", from, e.getMessage(), e);
            wa.sendText(from, "Sorry, something went wrong on our side. Please try again in a moment.");
        }
    }

    /**
     * Words the customer typed or said (a voice note's transcript): commands first, then the delivery
     * address when we're waiting for one, otherwise a shopping list. Matching ignores case and punctuation,
     * so "Cancel the order." works as well as "cancel".
     */
    private void route(String from, Merchant store, ShopCart cart, String text, boolean spoken) {
        String lower = normalize(text);

        CancelRequest many = cancelRequest(lower);
        if (many != null) {
            cancelOrders(from, store, many);
            return;
        }
        if (wantsCancel(lower)) {
            cancel(from, store, cart);
            return;
        }
        if ((cart.getStatus() == ShopCart.Status.AWAITING_SHOP_NAME || cart.getStatus() == ShopCart.Status.AWAITING_CONTACT)
                && !GREETINGS.contains(lower)) {
            boolean askingName = cart.getStatus() == ShopCart.Status.AWAITING_SHOP_NAME;
            String what = askingName ? "your shop or business name" : "the contact person's name";
            if (spoken) {
                wa.sendText(from, "Please type " + what + ", so we spell it right on your order.");
                return;
            }
            if (COMMAND_WORDS.contains(lower) || !text.matches(".*\\p{L}.*") || text.trim().length() < 2 || text.trim().length() > 80) {
                wa.sendText(from, "Please type " + what + " (for example \"" + (askingName ? "Mama Njeri Wines & Spirits" : "Jane Wanjiku")
                        + "\"). Send \"cancel\" to stop.");
                return;
            }
            String value = text.trim().replaceAll("\\s+", " ");
            if (askingName && shopNameFrom(text) != null) value = shopNameFrom(text);
            if (askingName) {
                // An existing shop typed by name goes on that shop's account instead of becoming a duplicate
                Merchant match = matchShop(value, distributorShops(store));
                cart.setShopMerchantId(match == null ? null : match.getId());
                cart.setShopName(match == null ? value : match.getBusinessName());
                cart.setPhotoShopName(null);
            } else {
                cart.setContactPerson(value);
            }
            save(cart);
            nextShopStep(from, store, cart);
            return;
        }
        if (cart.getStatus() == ShopCart.Status.AWAITING_ADDRESS && !GREETINGS.contains(lower)) {
            if (spoken) {
                // A misheard address costs a wasted delivery: ask for it in writing
                wa.sendText(from, "Please type your delivery address or share your location (📎 → Location), "
                        + "so we get it exactly right. Say or send \"cancel\" to go back to your cart.");
                return;
            }
            if (COMMAND_WORDS.contains(lower) || !text.matches(".*\\p{L}.*") || text.length() < 3) {
                // e.g. "Order" or "ok" while we wait for the address: don't save it as the address
                wa.sendText(from, "I still need your delivery address. Type it (e.g. \"Milimani, house 12, near the hospital\") "
                        + "or share your location (📎 → Location). Send \"cancel\" to go back to your cart.");
                return;
            }
            deliverTo(from, cart, store, text);
            return;
        }
        Integer qty = quantity(lower);
        if (cart.getPendingItemCode() != null && qty != null && cart.getPendingItemCode().startsWith(FIX_QTY)) {
            String code = cart.getPendingItemCode().substring(FIX_QTY.length());
            setItemQty(cart, code, qty);
            cart.setPendingItemCode(null);
            save(cart);
            String name = liveItem(store, code).map(Inventory::getItemName).orElse("that item");
            askNextOrShowCart(from, store, cart, "Changed to " + Math.min(clampQty(qty), MAX_QTY) + " x " + name + ".");
            return;
        }
        if (cart.getPendingItemCode() != null && qty != null) {
            addItem(cart, cart.getPendingItemCode(), qty);
            cart.setPendingItemCode(null);
            save(cart);
            showCart(from, store, cart, null);
            return;
        }
        if (GREETINGS.contains(lower) || lower.length() <= 2) {
            welcome(from, store, cart, null);
            return;
        }
        switch (lower) {
            case "cart", "kikapu", "basket", "my cart", "show cart", "show my cart" -> showCart(from, store, cart, null);
            case "clear", "futa", "clear cart", "clear my cart", "futa kikapu" -> clear(from, store, cart);
            case "browse", "products", "bidhaa", "browse products" -> categories(from, store);
            case "confirm", "confirm order", "confirm my order", "thibitisha", "thibitisha order" -> confirm(from, store, cart);
            default -> {
                String named = b2b() && !spoken ? shopNameFrom(text) : null;
                if (named != null) setShopByName(from, store, cart, named);
                else readList(from, store, cart, text);
            }
        }
    }

    /** Lower case, punctuation removed, spaces collapsed: "Cancel the order." → "cancel the order". */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Filler that doesn't change what a short cancel request means ("please cancel my order" → "cancel order"). */
    private static final Set<String> CANCEL_FILLER = Set.of("please", "pls", "plz", "tafadhali", "the", "my", "this", "that",
            "i", "want", "to", "would", "like", "nataka", "naomba", "yangu", "hii", "hiyo", "sasa", "now", "just", "kindly",
            "can", "you", "u", "ok", "okay", "sawa", "hapana", "no");
    private static final Set<String> CANCEL_PHRASES = Set.of("cancel", "cancel order", "cancel oda", "order cancel",
            "cancel it", "cancel everything", "cancel all", "cancel yote", "ghairi", "ghairi order", "ghairi oda", "ghairisha",
            "ghairisha order", "futa order", "futa oda", "sitaki", "sitaki tena", "sitaki order", "stop order");

    /**
     * Only a clear, short cancel request counts; "cancel the sugar and add milk" stays a shopping list.
     * Swahili "kucancel" (to cancel) is read as "cancel".
     */
    static boolean wantsCancel(String normalized) {
        if (CANCEL_WORDS.contains(normalized)) return true;
        List<String> words = new ArrayList<>();
        for (String w : normalized.split(" ")) {
            if (w.isEmpty() || CANCEL_FILLER.contains(w)) continue;
            words.add(w.equals("kucancel") ? "cancel" : w);
        }
        return !words.isEmpty() && words.size() <= 3 && CANCEL_PHRASES.contains(String.join(" ", words));
    }

    /** Button and list taps (ids like CAT:2, ITEM:code, QTY:code:3). Returns true when handled. */
    private boolean command(String from, Merchant store, ShopCart cart, String id) {
        String[] p = id.split(":", 3);
        switch (p[0]) {
            case "MENU" -> welcome(from, store, cart, null);
            case "BROWSE", "ADDMORE_BROWSE" -> categories(from, store);
            case "LIST" -> wa.sendText(from, (b2b()
                    ? "Type your order in one message, in English or Swahili. For example:\n\"Kibao quarter 24, Chrome mzinga 6, Tusker can crate 1\""
                    : "Type your shopping list in one message, in English or Swahili. For example:\n"
                    + "\"Unga Jogoo 2kg mbili, sukari 1kg, maziwa 3, Coke 500ml 6\"")
                    + (transcriber.isConfigured() ? "\n\n🎤 Or hold the mic and say your list as a voice note." : ""));
            case "PHOTO" -> wa.sendText(from, "📷 Send a clear photo of your " + (b2b() ? "order or stock list" : "shopping list") + " (handwritten is fine). "
                    + "Lay it flat in good light. I'll read it and show you everything before you order.");
            case "CAT" -> products(from, store, Integer.parseInt(p[1]), p.length > 2 ? Integer.parseInt(p[2]) : 0);
            case "ITEM" -> {
                Inventory item = liveItem(store, p[1]).orElse(null);
                if (item == null || !orderable(item)) {
                    wa.sendText(from, "Sorry, that item just ran out.");
                    return true;
                }
                cart.setPendingItemCode(item.getItemCode());
                save(cart);
                wa.sendButtons(from, item.getItemName() + " — KES " + money(priceOf(item, prices(store))) + ".\nHow many? Tap below or type a number.",
                        List.of(new WhatsAppCloudClient.Button("QTY:" + item.getItemCode() + ":1", "1"),
                                new WhatsAppCloudClient.Button("QTY:" + item.getItemCode() + ":2", "2"),
                                new WhatsAppCloudClient.Button("QTY:" + item.getItemCode() + ":3", "3")));
            }
            case "QTY" -> {
                addItem(cart, p[1], Integer.parseInt(p[2]));
                cart.setPendingItemCode(null);
                save(cart);
                showCart(from, store, cart, null);
            }
            case "PICK" -> answerQuestion(from, store, cart, p[1]);
            case "SKIPQ" -> answerQuestion(from, store, cart, null);
            case "CONFIRM" -> confirm(from, store, cart);
            case "ADDMORE" -> wa.sendButtons(from, "What else would you like?",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products"),
                            new WhatsAppCloudClient.Button("LIST", "Type a list")));
            case "CLEAR" -> clear(from, store, cart);
            case "ADDBACK" -> {
                List<Item> earlier;
                try {
                    earlier = cart.getPreviousItemsJson() == null ? List.of()
                            : json.readValue(cart.getPreviousItemsJson(), new TypeReference<List<Item>>() {});
                } catch (Exception e) {
                    earlier = List.of();
                }
                if (earlier.isEmpty() || cart.getStatus() != ShopCart.Status.OPEN) {
                    showCart(from, store, cart, null);
                    return true;
                }
                earlier.forEach(it -> addItem(cart, it.code(), it.qty()));
                cart.setPreviousItemsJson(null);
                save(cart);
                showCart(from, store, cart, "Added your earlier items.");
            }
            case "PAY" -> pay(from, p[1]);
            case "REORDER" -> reorder(from, store, cart);
            case "SHOPNAME_PHOTO" -> {
                if (cart.getStatus() != ShopCart.Status.AWAITING_SHOP_NAME || isBlank(cart.getPhotoShopName())) return false;
                cart.setShopName(cart.getPhotoShopName());
                cart.setShopMerchantId(null);
                cart.setPhotoShopName(null);
                save(cart);
                nextShopStep(from, store, cart);
            }
            case "SHOPPICK" -> {
                if (cart.getStatus() != ShopCart.Status.AWAITING_SHOP_NAME || p.length < 2) return false;
                Merchant picked = merchantRepository.findById(Long.valueOf(p[1])).orElse(null);
                if (picked == null) return false;
                cart.setShopMerchantId(picked.getId());
                cart.setShopName(picked.getBusinessName());
                cart.setPhotoShopName(null);
                save(cart);
                nextShopStep(from, store, cart);
            }
            case "SHOPNAME_MINE" -> {
                if (cart.getStatus() != ShopCart.Status.AWAITING_SHOP_NAME) return false;
                cart.setPhotoShopName(null);
                save(cart);
                nextShopStep(from, store, cart);
            }
            case "CONTACT_ME" -> {
                if (cart.getStatus() != ShopCart.Status.AWAITING_CONTACT || cart.getCustomerName() == null) return false;
                cart.setContactPerson(cart.getCustomerName());
                save(cart);
                nextShopStep(from, store, cart);
            }
            case "CANCELORDERS" -> {
                if (p.length < 2) return false;
                cancelConfirmed(from, store, List.of(p[1].split(",")));
            }
            case "KEEPORDERS" -> wa.sendText(from, "OK, your orders stay as they are.");
            case "PAYLATER" -> wa.sendText(from, "Sawa! Pay when your order is delivered. Send \"hi\" anytime to order again.");
            default -> {
                return false;
            }
        }
        return true;
    }

    // ───────────────────────────── conversation steps ─────────────────────────────

    private void welcome(String from, Merchant store, ShopCart cart, String note) {
        if (b2b()) {
            shopWelcome(from, store, cart, note);
            return;
        }
        String name = cart.getCustomerName() == null ? "" : " " + cart.getCustomerName().split(" ")[0];
        String body = (note == null ? "" : note + "\n\n")
                + "Karibu " + store.getBusinessName() + name + "! 🛒\n"
                + "Order your groceries here and pay with M-Pesa. Delivery is free.\n\nHow would you like to order?"
                + (transcriber.isConfigured() ? "\n🎤 You can also send your list as a voice note." : "")
                + countHint(from, store);
        wa.sendButtons(from, body, List.of(
                new WhatsAppCloudClient.Button("BROWSE", "Browse products"),
                new WhatsAppCloudClient.Button("LIST", "Send a list"),
                new WhatsAppCloudClient.Button("PHOTO", "Send a photo")));
    }

    /** B2B: the shop's name when we know it, and "Reorder my usual" first when it has ordered before. */
    private void shopWelcome(String from, Merchant store, ShopCart cart, String note) {
        Merchant shop = knownShop(from, store);
        String who = shop != null ? " " + shop.getBusinessName()
                : cart.getCustomerName() == null ? "" : " " + cart.getCustomerName().split(" ")[0];
        String body = (note == null ? "" : note + "\n\n")
                + "Karibu " + store.getBusinessName() + who + "! 🍾\n"
                + "Order stock for your shop here: type your order, send a photo of your list"
                + (transcriber.isConfigured() ? ", or send a voice note 🎤" : "") + ".\n"
                + "Wholesale prices. Pay on delivery or with M-Pesa."
                + countHint(from, store);
        List<WhatsAppCloudClient.Button> buttons = new ArrayList<>();
        buttons.add(lastOrderItems(shop, store).isEmpty()
                ? new WhatsAppCloudClient.Button("BROWSE", "Browse products")
                : new WhatsAppCloudClient.Button("REORDER", "Reorder my usual"));
        buttons.add(new WhatsAppCloudClient.Button("LIST", "Type an order"));
        buttons.add(new WhatsAppCloudClient.Button("PHOTO", "Send a photo"));
        wa.sendButtons(from, body, buttons);
    }

    /** Staff numbers of the store are reminded they can do the stock count here. */
    private String countHint(String from, Merchant store) {
        return stockCount.isStaff(from, String.valueOf(store.getId())) ? "\n\n📦 Staff: send *count* to do tonight's stock count, or *stock* with a supplier receipt to add stock." : "";
    }

    /** B2B: the shop's last order from this distributor, back in the cart at today's prices. */
    private void reorder(String from, Merchant store, ShopCart cart) {
        List<Item> last = lastOrderItems(knownShop(from, store), store);
        if (last.isEmpty()) {
            welcome(from, store, cart, "I couldn't find an earlier order for your shop.");
            return;
        }
        int added = 0;
        for (Item it : last) {
            Inventory i = liveItem(store, it.code()).orElse(null);
            if (i == null || !orderable(i)) continue;
            addItem(cart, i.getItemCode(), it.qty());
            added++;
        }
        save(cart);
        if (added == 0) {
            welcome(from, store, cart, "None of the products from your last order are available right now.");
            return;
        }
        showCart(from, store, cart, "Here's your last order again, at today's prices. Change it by typing more, or confirm.");
    }

    private void categories(String from, Merchant store) {
        List<String> cats = categoryNames(catalogue(store));
        List<WhatsAppCloudClient.Row> rows = new ArrayList<>();
        for (int i = 0; i < cats.size() && i < 10; i++) {
            rows.add(new WhatsAppCloudClient.Row("CAT:" + i, cats.get(i), null));
        }
        wa.sendList(from, "Pick a category to see what's in stock.", "Categories", rows);
    }

    private void products(String from, Merchant store, int categoryIndex, int page) {
        List<Inventory> all = catalogue(store);
        List<String> cats = categoryNames(all);
        if (categoryIndex < 0 || categoryIndex >= cats.size()) {
            categories(from, store);
            return;
        }
        String cat = cats.get(categoryIndex);
        List<Inventory> inCat = all.stream()
                .filter(i -> cat.equals(i.getProductCategory() == null ? "Other" : i.getProductCategory()) && orderable(i))
                .toList();
        Map<String, BigDecimal> prices = prices(store);
        int from0 = page * PAGE;
        List<WhatsAppCloudClient.Row> rows = new ArrayList<>();
        for (int i = from0; i < inCat.size() && i < from0 + PAGE; i++) {
            Inventory item = inCat.get(i);
            rows.add(new WhatsAppCloudClient.Row("ITEM:" + item.getItemCode(), item.getItemName(), rowDescription(item, priceOf(item, prices))));
        }
        if (from0 + PAGE < inCat.size()) rows.add(new WhatsAppCloudClient.Row("CAT:" + categoryIndex + ":" + (page + 1), "More " + cat, "Next page"));
        if (rows.isEmpty()) {
            wa.sendText(from, "Everything in " + cat + " is out of stock right now.");
            return;
        }
        wa.sendList(from, cat + (page > 0 ? " (page " + (page + 1) + ")" : "") + ": tap a product to add it.", "Products", rows);
    }

    /** A typed shopping list: Claude reads it, then every line is checked against live stock and prices. */
    private void readList(String from, Merchant store, ShopCart cart, String text) {
        List<Inventory> catalogue = catalogue(store);
        if (!reader.isConfigured()) {
            wa.sendButtons(from, "Typed lists aren't switched on yet. Please browse our products instead.",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        ShoppingListReader.Reading reading;
        try {
            reading = reader.read(text, catalogue, vertical(store), prices(store));
            if (reading == null) throw new IllegalStateException("No reading returned");
        } catch (Exception e) {
            log.warn("Shopping list from {} could not be read: {}", from, e.getMessage());
            wa.sendButtons(from, "Sorry, I couldn't read that list just now. Please send it again, or browse our products.",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        applyReading(from, store, cart, catalogue, reading);
    }

    /** A photo of a shopping list: downloaded from WhatsApp, read by Claude, then treated exactly like a typed list. */
    @SuppressWarnings("unchecked")
    private void readPhoto(String from, Merchant store, ShopCart cart, Map<String, Object> message) {
        if (!reader.isConfigured()) {
            wa.sendButtons(from, "Photo orders aren't switched on yet. Please browse our products instead.",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        Map<String, Object> image = message != null && message.get("image") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        WhatsAppCloudClient.Media media = wa.downloadMedia(image.get("id") == null ? null : image.get("id").toString());
        if (media == null) {
            wa.sendButtons(from, "Sorry, I couldn't open that photo. Please send it again, or type your list.",
                    List.of(new WhatsAppCloudClient.Button("LIST", "Type a list"),
                            new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        mediaArchive.save(media.bytes(), media.mimeType(), "photo", from);
        wa.sendText(from, "📷 Got your photo, reading your list…");
        List<Inventory> catalogue = catalogue(store);
        ShoppingListReader.Reading reading;
        try {
            String caption = image.get("caption") == null ? null : image.get("caption").toString();
            reading = reader.readPhoto(media.bytes(), media.mimeType(), caption, catalogue, vertical(store), prices(store));
            if (reading == null) throw new IllegalStateException("No reading returned");
        } catch (Exception e) {
            log.warn("Shopping list photo from {} could not be read: {}", from, e.getMessage());
            wa.sendButtons(from, "Sorry, I couldn't read that photo. Try a clearer photo in good light, or type your list.",
                    List.of(new WhatsAppCloudClient.Button("LIST", "Type a list"),
                            new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        // A photo is a whole order (a receipt, an order page): don't mix it into what's already in the cart
        int earlier = items(cart).size();
        if (earlier > 0 || cart.getStatus() != ShopCart.Status.OPEN) {
            String earlierItems = cart.getItemsJson();
            emptyCart(cart);
            if (earlier > 0) {
                cart.setPreviousItemsJson(earlierItems);
                save(cart);
            }
        }
        if (b2b()) useShopNamedOnPhoto(from, store, cart, reading.shopName());
        applyReading(from, store, cart, catalogue, reading);
        if (earlier > 0) {
            wa.sendButtons(from, "📋 I made a new order from this photo. Your earlier cart (" + earlier + (earlier == 1 ? " item" : " items")
                            + ") is set aside: tap below if you want it in this order too.",
                    List.of(new WhatsAppCloudClient.Button("ADDBACK", "Add earlier items")));
        }
    }

    /**
     * A voice note: downloaded from WhatsApp, turned into text, then read like a typed list. The customer sees
     * what we heard and the cart it made, and nothing is ordered until they tap Confirm order.
     */
    @SuppressWarnings("unchecked")
    private void readVoice(String from, Merchant store, ShopCart cart, Map<String, Object> message) {
        if (!transcriber.isConfigured()) {
            wa.sendButtons(from, "Voice orders aren't switched on yet. Please type your list or browse our products.",
                    List.of(new WhatsAppCloudClient.Button("LIST", "Type a list"),
                            new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        Map<String, Object> audio = message != null && message.get("audio") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        WhatsAppCloudClient.Media media = wa.downloadMedia(audio.get("id") == null ? null : audio.get("id").toString());
        if (media == null) {
            wa.sendButtons(from, "Sorry, I couldn't open that voice note. Please send it again, or type your list.",
                    List.of(new WhatsAppCloudClient.Button("LIST", "Type a list"),
                            new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        mediaArchive.save(media.bytes(), media.mimeType(), "voice", from);
        List<Inventory> catalogue = catalogue(store);
        String heard;
        try {
            heard = transcriber.transcribe(media.bytes(), media.mimeType(), catalogue,
                    vertical(store) == ShoppingListReader.Vertical.LIQUOR ? "Sizes: kota, kwota, robo, nusu, half, mzinga, crate" : null);
        } catch (Exception e) {
            log.warn("Voice note from {} could not be transcribed: {}", from, e.getMessage());
            heard = null;
        }
        if (heard == null || heard.isBlank() || !heard.matches("(?s).*\\p{L}.*")) {
            wa.sendButtons(from, "Sorry, I couldn't hear that clearly. Try again somewhere quieter, or type your list.",
                    List.of(new WhatsAppCloudClient.Button("LIST", "Type a list"),
                            new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        // Say back what we heard first, so a misheard word is caught before anything happens
        wa.sendText(from, "🎤 I heard: \"" + truncate(heard, 500) + "\"");
        // Then treat it like typed words: "cancel", "show my cart" etc. are commands; anything else is a list
        route(from, store, cart, heard, true);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * B2B: a photographed receipt or order page often names the shop it's for. If it matches one of the
     * distributor's shops, the order goes on that shop; otherwise the name is used for a new shop at checkout
     * (so it isn't asked again). The distributor's own name on its receipt is ignored.
     */
    private void useShopNamedOnPhoto(String from, Merchant store, ShopCart cart, String named) {
        if (isBlank(named) || named.replaceAll("[^\\p{L}\\p{N}]", "").length() < 3
                || coreName(named).equals(coreName(store.getBusinessName()))) return;
        String name = named.trim().replaceAll("\\s+", " ");
        Merchant match = matchShop(name, distributorShops(store));
        if (match != null) {
            cart.setShopMerchantId(match.getId());
            cart.setShopName(match.getBusinessName());
            wa.sendText(from, "📄 This order is for " + match.getBusinessName() + ". I'll put it on that shop's account.");
        } else {
            // Not one of the distributor's shops: maybe new, maybe misread. Confirm it at checkout (one tap)
            // instead of registering a misread name as a new shop
            cart.setPhotoShopName(truncate(name, 120));
        }
        save(cart);
    }

    /** "shop is MD Wines", "shop: MD Wines", "for MD Wines", "ni ya MD Wines", "duka ni MD Wines" → "MD Wines"; otherwise null. */
    static String shopNameFrom(String text) {
        if (text == null) return null;
        Matcher m = SHOP_IS.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group(1).trim().replaceAll("[.!]+$", "").replaceAll("\\s+", " ");
        // Quantities mean it's an order line ("for kibao 6"), not a shop name
        return name.length() < 2 || name.length() > 80 || name.matches(".*\\d.*") ? null : name;
    }

    private static final Pattern SHOP_IS = Pattern.compile(
            "(?i)^(?:(?:the\\s+)?shop(?:\\s+name)?\\s*(?:is|:|=|-)|business(?:\\s+name)?\\s*(?:is|:)|order\\s+for|this\\s+is\\s+for|for|ni\\s+ya|duka\\s+ni|duka\\s*:)\\s+(.+)$");

    /** "shop is X": puts this order on shop X (one of the distributor's shops, or a new one at checkout). */
    private void setShopByName(String from, Merchant store, ShopCart cart, String named) {
        Merchant match = matchShop(named, distributorShops(store));
        cart.setShopMerchantId(match == null ? null : match.getId());
        cart.setShopName(match == null ? truncate(named, 120) : match.getBusinessName());
        cart.setPhotoShopName(null);
        save(cart);
        String note = match != null ? "OK, this order is for " + match.getBusinessName() + "."
                : "OK, this order is for " + named + ". It's a new shop for " + store.getBusinessName() + ", so I'll register it when you confirm.";
        if (items(cart).isEmpty()) wa.sendText(from, note + " Now send the order: type it, send a photo or a voice note.");
        else showCart(from, store, cart, note);
    }

    /** Shops this phone number orders for at this distributor (its CRM contacts), most recent first. */
    private List<Merchant> shopsOfNumber(String from, Merchant store) {
        List<Merchant> shops = new ArrayList<>();
        List<CrmContact> mine = new ArrayList<>(contacts.findByNumber(from));
        mine.sort(Comparator.comparing((CrmContact c) -> c.getUpdatedAt() == null ? LocalDateTime.MIN : c.getUpdatedAt()).reversed());
        for (CrmContact c : mine) {
            if (!store.getId().equals(c.getDistributorId())) continue;
            merchantRepository.findById(c.getShopMerchantId())
                    .filter(m -> shops.stream().noneMatch(x -> coreName(x.getBusinessName()).equals(coreName(m.getBusinessName()))))
                    .ifPresent(shops::add);
        }
        return shops;
    }

    /** Shops that have ordered from this distributor, plus shops registered under it (e.g. from WhatsApp). */
    private List<Merchant> distributorShops(Merchant store) {
        Map<Long, Merchant> shops = new LinkedHashMap<>();
        Map<Long, Long> orderCounts = new HashMap<>();
        for (Object[] row : shopQueries.orderSummaryByShop(store.getId())) {
            if (row[0] instanceof Long id) orderCounts.put(id, row[1] instanceof Number n ? n.longValue() : 0L);
        }
        // Most-ordering shops first, so a duplicate name resolves to the shop the distributor really uses
        orderCounts.entrySet().stream().sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .forEach(e -> merchantRepository.findById(e.getKey()).ifPresent(m -> shops.put(m.getId(), m)));
        merchantRepository.findByDistributor_Id(store.getId()).forEach(m -> shops.putIfAbsent(m.getId(), m));
        return new ArrayList<>(shops.values());
    }

    /** Words that don't tell two bars apart ("Baraka Bar" = "Baraka Bar & Lounge Ltd"). */
    private static final Set<String> GENERIC_NAME_WORDS = Set.of("bar", "bars", "lounge", "pub", "inn", "liquor", "liquors",
            "wines", "wine", "spirits", "and", "the", "ltd", "limited", "enterprises", "enterprise", "shop", "store", "stores",
            "club", "restaurant", "hotel", "co", "company", "kenya");

    static String coreName(String name) {
        if (name == null) return "";
        List<String> words = new ArrayList<>();
        for (String w : normalize(name).split(" ")) {
            if (!w.isEmpty() && !GENERIC_NAME_WORDS.contains(w)) words.add(w);
        }
        return words.isEmpty() ? normalize(name).replace(" ", "") : String.join("", words);
    }

    /**
     * The shop whose name matches what's written: same name ignoring case, punctuation and words like "bar" or
     * "wines", allowing one wrong letter on longer names (a misread handwritten letter). First in list order wins.
     */
    static Merchant matchShop(String written, List<Merchant> shops) {
        String exact = normalize(written);
        if (exact.length() < 2) return null;
        for (Merchant m : shops) if (normalize(m.getBusinessName()).equals(exact)) return m;
        String want = coreName(written);
        if (want.length() < 2) return null;
        for (Merchant m : shops) if (coreName(m.getBusinessName()).equals(want)) return m;
        if (want.length() >= 5) {
            for (Merchant m : shops) if (withinOneEdit(coreName(m.getBusinessName()), want)) return m;
        }
        Set<String> writtenWords = coreWords(written);
        Merchant only = null;
        for (Merchant m : shops) {
            Set<String> core = coreWords(m.getBusinessName());
            if (core.isEmpty() || !writtenWords.containsAll(core)) continue;
            if (only != null && !coreName(only.getBusinessName()).equals(coreName(m.getBusinessName()))) return null; // ambiguous
            if (only == null) only = m;
        }
        return only;
    }

    /** Distinctive words of a name: "MD Wines Ltd" → [md]; "Baraka Bar & Lounge" → [baraka]. */
    static Set<String> coreWords(String name) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : normalize(name).split(" ")) {
            if (w.length() >= 2 && !GENERIC_NAME_WORDS.contains(w)) words.add(w);
        }
        return words;
    }

    private static boolean withinOneEdit(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, edits = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (a.length() > b.length()) i++;
            else if (b.length() > a.length()) j++;
            else { i++; j++; }
        }
        return edits + (a.length() - i) + (b.length() - j) <= 1;
    }

    /** Adds what was clearly read, asks about unclear lines, and names what we don't have. Never orders by itself. */
    private void applyReading(String from, Merchant store, ShopCart cart, List<Inventory> catalogue,
                              ShoppingListReader.Reading reading) {
        Map<String, Inventory> byCode = new HashMap<>();
        catalogue.forEach(i -> byCode.put(i.getItemCode(), i));

        logReading(from, reading, byCode);
        if (cart.getPendingItemCode() != null && cart.getPendingItemCode().startsWith(FIX_QTY)) cart.setPendingItemCode(null);
        Usage usage = b2b() ? usage(from, store, cart) : Usage.NONE;

        List<String> added = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Question> questions = new ArrayList<>(questions(cart));
        for (ShoppingListReader.Line line : reading.lines() == null ? List.<ShoppingListReader.Line>of() : reading.lines()) {
            int qty = clampQty(line.quantity());
            String requested = line.requested() == null ? "item" : line.requested().trim();
            Inventory sure = "high".equalsIgnoreCase(line.confidence()) ? byCode.get(line.itemCode()) : null;
            List<String> options = new ArrayList<>();
            if (line.itemCode() != null && byCode.containsKey(line.itemCode()) && sure == null) options.add(line.itemCode());
            for (String alt : line.alternatives() == null ? List.<String>of() : line.alternatives()) {
                if (byCode.containsKey(alt) && !options.contains(alt)) options.add(alt);
            }
            options.removeIf(code -> !orderable(byCode.get(code)));

            if (sure != null) {
                if (!orderable(sure)) {
                    missing.add(requested + " (out of stock)");
                } else {
                    int packs = packsFor(sure, line.requestedTotal(), line.requestedUnit(), qty);
                    addItem(cart, sure.getItemCode(), packs);
                    added.add(packs + " x " + sure.getItemName());
                }
            } else if (options.size() == 1) {
                Inventory only = byCode.get(options.get(0));
                int packs = packsFor(only, line.requestedTotal(), line.requestedUnit(), qty);
                addItem(cart, only.getItemCode(), packs);
                added.add(packs + " x " + only.getItemName());
            } else if (!options.isEmpty()) {
                String usual = usualSize(options, byCode, usage);
                if (usual != null) {
                    Inventory pick = byCode.get(usual);
                    int packs = packsFor(pick, line.requestedTotal(), line.requestedUnit(), qty);
                    addItem(cart, pick.getItemCode(), packs);
                    added.add(packs + " x " + pick.getItemName()
                            + (usage.shop().getOrDefault(usual, 0) > 0 ? " (your usual size)" : " (most ordered size)"));
                } else {
                    questions.add(new Question(requested, qty, options.subList(0, Math.min(options.size(), 9)),
                            line.requestedTotal(), line.requestedUnit()));
                }
            } else {
                missing.add(requested);
            }
        }
        setQuestions(cart, questions);
        save(cart);

        StringBuilder sb = new StringBuilder();
        if (!added.isEmpty()) {
            sb.append("Added to your cart:\n");
            added.forEach(a -> sb.append("• ").append(a).append('\n'));
        }
        if (!missing.isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("Sorry, we don't have: ").append(String.join(", ", missing)).append('.');
        }
        if (added.isEmpty() && questions.isEmpty()) {
            wa.sendButtons(from, (sb.isEmpty() ? "I couldn't find those items." : sb.toString())
                            + "\nTry other names, or browse our products.",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        if (!sb.isEmpty()) wa.sendText(from, sb.toString().trim());
        askNextOrShowCart(from, store, cart);
    }

    /** What was read from a list, photo or voice note, for checking accuracy later: "kibao robo 24 → 23-16C61U x24 (high)". */
    private static void logReading(String from, ShoppingListReader.Reading reading, Map<String, Inventory> byCode) {
        if (reading == null || reading.lines() == null) return;
        StringBuilder sb = new StringBuilder();
        for (ShoppingListReader.Line l : reading.lines()) {
            Inventory i = l.itemCode() == null ? null : byCode.get(l.itemCode());
            sb.append("\n  ").append(l.requested()).append(" → ").append(i == null ? String.valueOf(l.itemCode()) : i.getItemName())
                    .append(" x").append(l.quantity()).append(" (").append(l.confidence()).append(')');
            if (l.alternatives() != null && !l.alternatives().isEmpty()) sb.append(" alts ").append(l.alternatives());
        }
        log.info("List read for {}: shop on list={}, {} lines{}", from, reading.shopName(), reading.lines().size(), sb);
    }

    /** Units ordered per item code in the last 90 days: by this shop, and by all of the distributor's shops. */
    record Usage(Map<String, Integer> shop, Map<String, Integer> all) {
        static final Usage NONE = new Usage(Map.of(), Map.of());
    }

    private Usage usage(String from, Merchant store, ShopCart cart) {
        Merchant shop = orderingShop(from, store, cart);
        Long shopId = shop == null ? null : shop.getId();
        Usage u = tx.execute(status -> {
            Map<String, Integer> mine = new HashMap<>(), all = new HashMap<>();
            for (Order o : shopQueries.ordersWithItemsSince(store.getId(), LocalDateTime.now().minusDays(90))) {
                if ("CANCELLED".equals(String.valueOf(o.getStatus())) || o.getItems() == null) continue;
                boolean ours = shopId != null && o.getMerchant() != null && shopId.equals(o.getMerchant().getId());
                for (var i : o.getItems()) {
                    if (i.getItemCode() == null || i.getQuantity() == null) continue;
                    all.merge(i.getItemCode(), i.getQuantity(), Integer::sum);
                    if (ours) mine.merge(i.getItemCode(), i.getQuantity(), Integer::sum);
                }
            }
            return new Usage(mine, all);
        });
        return u == null ? Usage.NONE : u;
    }

    /**
     * When the options are sizes of one product ("Kibao" → 1/4, 1/2, 3/4), the size this shop orders most, else the
     * size the distributor's shops order most. Different products ("vodka" → Chrome or Smirnoff) are still asked.
     */
    static String usualSize(List<String> options, Map<String, Inventory> byCode, Usage usage) {
        if (options.size() < 2 || !sameProduct(options.stream().map(c -> byCode.get(c).getItemName()).toList())) return null;
        for (Map<String, Integer> counts : List.of(usage.shop(), usage.all())) {
            String best = null;
            int bestUnits = 0;
            for (String code : options) {
                int units = counts.getOrDefault(code, 0);
                if (units > bestUnits) { best = code; bestUnits = units; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static final Pattern SIZE_WORDS = Pattern.compile(
            "\\b\\d/\\d\\b|\\b\\d+\\s*(ml|l|ltr|litres?)\\b|\\b(250|330|350|375|500|750|1000)\\b|\\b(can|bottle|tetra|and)\\b",
            Pattern.CASE_INSENSITIVE);

    /** "KIBAO 1/4", "KIBAO 1/2" and "KIBAO 3/4" are one product in three sizes; so are "CHROME 3/4" and "CHROME VODKA 1/4". */
    static boolean sameProduct(List<String> names) {
        List<Set<String>> words = new ArrayList<>();
        for (String n : names) {
            Set<String> w = new LinkedHashSet<>(Arrays.asList(normalize(SIZE_WORDS.matcher(String.valueOf(n)).replaceAll(" ")).split(" ")));
            w.remove("");
            if (w.isEmpty()) return false;
            words.add(w);
        }
        for (int a = 0; a < words.size(); a++) {
            for (int b = a + 1; b < words.size(); b++) {
                Set<String> x = new HashSet<>(words.get(a)), y = new HashSet<>(words.get(b));
                x.removeAll(DRINK_TYPE_WORDS);
                y.removeAll(DRINK_TYPE_WORDS);
                if (x.isEmpty() || !x.equals(y)) return false;
            }
        }
        return true;
    }

    /** Words some sizes carry in their name and others don't ("CHROME 3/4" vs "CHROME VODKA 1/4"). */
    private static final Set<String> DRINK_TYPE_WORDS = Set.of("vodka", "gin", "whisky", "whiskey", "brandy", "rum", "wine", "spirit");

    private void askNextOrShowCart(String from, Merchant store, ShopCart cart) {
        askNextOrShowCart(from, store, cart, null);
    }

    private void askNextOrShowCart(String from, Merchant store, ShopCart cart, String note) {
        List<Question> qs = questions(cart);
        if (qs.isEmpty()) {
            showCart(from, store, cart, note);
            return;
        }
        Question q = qs.get(0);
        List<WhatsAppCloudClient.Row> rows = new ArrayList<>();
        Map<String, BigDecimal> prices = prices(store);
        for (String code : q.options()) {
            liveItem(store, code).ifPresent(i ->
                    rows.add(new WhatsAppCloudClient.Row("PICK:" + code, i.getItemName(), rowDescription(i, priceOf(i, prices)))));
        }
        rows.add(new WhatsAppCloudClient.Row("SKIPQ", "None of these", "Skip this item"));
        String amount = q.total() != null && q.unit() != null
                ? " (" + trim(q.total()) + " " + q.unit() + " in total)"
                : " (" + q.qty() + (q.qty() == 1 ? " unit" : " units") + ")";
        wa.sendList(from, "Which \"" + q.requested() + "\" would you like?" + amount
                        + "\nTap the right one below. Wrong quantity? Send the right number after you pick.",
                "Choose", rows);
    }

    private void answerQuestion(String from, Merchant store, ShopCart cart, String code) {
        List<Question> qs = new ArrayList<>(questions(cart));
        if (qs.isEmpty()) {
            showCart(from, store, cart, null);
            return;
        }
        Question q = qs.remove(0);
        Inventory picked = code == null ? null : liveItem(store, code).orElse(null);
        String note = null;
        if (picked != null) {
            int packs = packsFor(picked, q.total(), q.unit(), q.qty());
            addItem(cart, code, packs);
            // The quantity came from a photo or voice note and may be misread: a number sent next replaces it
            cart.setPendingItemCode(FIX_QTY + code);
            note = "Added " + packs + " x " + picked.getItemName() + ". Wrong quantity? Send the right number.";
        }
        setQuestions(cart, qs);
        save(cart);
        askNextOrShowCart(from, store, cart, note);
    }

    /** Marks pendingItemCode as "a number now corrects this item's quantity" (not "adds to it"). */
    private static final String FIX_QTY = "=";

    void setItemQty(ShopCart cart, String code, int qty) {
        List<Item> items = new ArrayList<>(items(cart));
        items.removeIf(i -> i.code().equals(code));
        items.add(new Item(code, Math.min(clampQty(qty), MAX_QTY)));
        try {
            cart.setItemsJson(json.writeValueAsString(items));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void showCart(String from, Merchant store, ShopCart cart, String note) {
        List<Item> items = items(cart);
        if (items.isEmpty()) {
            welcome(from, store, cart, "Your cart is empty.");
            return;
        }
        StringBuilder sb = new StringBuilder(note == null ? "" : note + "\n\n");
        sb.append("🛒 Your cart at ").append(store.getBusinessName()).append(":\n");
        BigDecimal total = BigDecimal.ZERO;
        int shown = 0;
        Map<String, BigDecimal> prices = prices(store);
        for (Item it : items) {
            Inventory i = liveItem(store, it.code()).orElse(null);
            BigDecimal price = priceOf(i, prices);
            if (i == null || price == null) continue;
            BigDecimal line = price.multiply(BigDecimal.valueOf(it.qty()));
            total = total.add(line);
            if (shown++ < 15) {
                sb.append(it.qty()).append(" x ").append(i.getItemName()).append(" — KES ").append(money(line));
                // B2B orders aren't capped at today's stock: say what the distributor will confirm
                if (b2b() && available(i) < it.qty()) sb.append(available(i) <= 0 ? " ⚠️ out of stock now" : " ⚠️ only " + available(i) + " in stock");
                sb.append('\n');
            }
        }
        if (shown > 15) sb.append("…and ").append(shown - 15).append(" more\n");
        sb.append("\nTotal: KES ").append(money(total)).append(b2b() ? "\nPayment: on delivery, or M-Pesa" : "\nDelivery: free");
        wa.sendButtons(from, sb.toString(), List.of(
                new WhatsAppCloudClient.Button("CONFIRM", "Confirm order"),
                new WhatsAppCloudClient.Button("ADDMORE", "Add more"),
                new WhatsAppCloudClient.Button("CLEAR", "Clear cart")));
    }

    private void clear(String from, Merchant store, ShopCart cart) {
        emptyCart(cart);
        welcome(from, store, cart, "Your cart is now empty.");
    }

    /**
     * "cancel" always empties the cart in progress. With nothing in the cart it cancels the customer's latest
     * unpaid order. A paid order is left for the store to handle (refund first).
     */
    private void cancel(String from, Merchant store, ShopCart cart) {
        boolean hadCart = !items(cart).isEmpty() || !questions(cart).isEmpty()
                || cart.getStatus() != ShopCart.Status.OPEN;
        Order order = latestOrder(from, store);
        boolean unpaid = order != null && "PENDING".equals(order.getStatus().name());

        if (hadCart) {
            // What they're stopping is the cart in front of them; an older unpaid order needs its own "cancel"
            emptyCart(cart);
            welcome(from, store, cart, "OK, cancelled. Nothing was ordered and your cart is now empty."
                    + (unpaid ? "\n\nYour earlier order " + order.getOrderCode() + " (KES " + money(order.getTotalAmount())
                    + ") is still open: send \"cancel\" again to cancel it too." : ""));
            return;
        }
        if (order == null || "CANCELLED".equals(order.getStatus().name())) {
            wa.sendText(from, "You don't have an open order to cancel. Send \"hi\" to start a new one.");
            return;
        }
        if (!unpaid) {
            wa.sendText(from, "Order " + order.getOrderCode() + " is already " + ("PAID".equals(order.getStatus().name()) ? "paid" : "on its way")
                    + ", so it can't be cancelled here. Please call " + store.getBusinessName() + " and we'll sort it out.");
            return;
        }
        try {
            orderService.cancelOrder(order.getOrderCode(), String.valueOf(store.getId()), null, "Cancelled by the customer on WhatsApp");
            awaitingPayment.remove(order.getOrderCode());
            wa.sendText(from, "Order " + order.getOrderCode() + " is cancelled. Send \"hi\" anytime to order again.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            wa.sendText(from, "Order " + order.getOrderCode() + " can't be cancelled: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Cancelling order {} for {} failed", order.getOrderCode(), from, e);
            wa.sendText(from, "Sorry, I couldn't cancel order " + order.getOrderCode() + " just now. Please try again, or call "
                    + store.getBusinessName() + ".");
        }
    }

    /** "cancel order 375531" (codes) or "cancel the last two orders" (a count; Integer.MAX_VALUE = all). */
    record CancelRequest(List<String> codes, int count) {}

    private static final Pattern CANCEL_CODES = Pattern.compile("^(?:please |pls )?(?:cancel|ghairi|futa)(?: the)?(?: my)? orders? (\\d{4,}(?:(?: and | |, ?)\\d{4,})*)$");
    private static final Pattern CANCEL_LAST = Pattern.compile("^(?:please |pls )?(?:cancel|ghairi|futa)(?: the| my)? (?:last|previous|recent) (\\w+) orders?$");
    private static final Pattern CANCEL_ALL = Pattern.compile("^(?:please |pls )?(?:cancel|ghairi|futa) all(?: my| the)? orders?$");

    static CancelRequest cancelRequest(String normalized) {
        Matcher m = CANCEL_CODES.matcher(normalized);
        if (m.matches()) return new CancelRequest(Arrays.asList(m.group(1).replaceAll("[^0-9]+", " ").trim().split(" ")), 0);
        m = CANCEL_LAST.matcher(normalized);
        if (m.matches()) {
            Integer n = m.group(1).matches("\\d+") ? Integer.valueOf(m.group(1)) : NUMBER_WORDS.get(m.group(1));
            return n == null || n < 1 ? null : new CancelRequest(List.of(), Math.min(n, 10));
        }
        if (CANCEL_ALL.matcher(normalized).matches()) return new CancelRequest(List.of(), Integer.MAX_VALUE);
        return null;
    }

    /** An open order, summarised inside a transaction (the shop is loaded lazily). */
    record OpenOrder(String code, String shop, BigDecimal total) {}

    /** Unpaid, undelivered orders this number placed on WhatsApp at this store, newest first. */
    private List<OpenOrder> openOrdersOf(String from, Merchant store) {
        List<OpenOrder> open = tx.execute(status -> {
            List<OpenOrder> list = new ArrayList<>();
            for (ShopCart c : carts.findByPhoneAndStoreMerchantIdAndStatusOrderByIdDesc(from, store.getId(), ShopCart.Status.ORDERED)) {
                if (c.getOrderCode() == null) continue;
                orderRepository.findByOrderCode(c.getOrderCode())
                        .filter(o -> "PENDING".equals(o.getStatus().name()) || "PENDING_PAYMENT".equals(o.getStatus().name()))
                        .ifPresent(o -> list.add(new OpenOrder(o.getOrderCode(),
                                o.getMerchant() == null ? null : o.getMerchant().getBusinessName(), o.getTotalAmount())));
            }
            return list;
        });
        return open == null ? List.of() : open;
    }

    /** Named orders are cancelled at once; "the last two" / "all" are listed first with Yes / No buttons. */
    private void cancelOrders(String from, Merchant store, CancelRequest req) {
        List<OpenOrder> open = openOrdersOf(from, store);
        if (!req.codes().isEmpty()) {
            List<String> mine = open.stream().map(OpenOrder::code).toList();
            List<String> notFound = req.codes().stream().filter(c -> !mine.contains(c)).toList();
            List<String> ok = req.codes().stream().filter(mine::contains).distinct().toList();
            if (!notFound.isEmpty()) {
                wa.sendText(from, "I can't cancel order " + String.join(", ", notFound) + ": it isn't an open order from this number "
                        + "(it may be paid, delivered or already cancelled).");
            }
            if (!ok.isEmpty()) cancelConfirmed(from, store, ok);
            return;
        }
        List<OpenOrder> chosen = open.subList(0, Math.min(req.count(), open.size()));
        if (chosen.isEmpty()) {
            wa.sendText(from, "You don't have any open orders to cancel.");
            return;
        }
        StringBuilder sb = new StringBuilder("Cancel " + (chosen.size() == 1 ? "this order" : "these " + chosen.size() + " orders") + "?\n");
        for (OpenOrder o : chosen) {
            sb.append("• ").append(o.code()).append(" — ").append(o.shop() == null ? "" : o.shop() + ", ")
                    .append("KES ").append(money(o.total())).append('\n');
        }
        if (req.count() != Integer.MAX_VALUE && chosen.size() < req.count()) {
            sb.append("(You only have ").append(chosen.size()).append(" open ").append(chosen.size() == 1 ? "order" : "orders").append(".)\n");
        }
        String codes = String.join(",", chosen.stream().map(OpenOrder::code).toList());
        wa.sendButtons(from, sb.toString().trim(), List.of(
                new WhatsAppCloudClient.Button("CANCELORDERS:" + codes, chosen.size() == 1 ? "Yes, cancel it" : "Yes, cancel " + chosen.size()),
                new WhatsAppCloudClient.Button("KEEPORDERS", "No, keep them")));
    }

    private void cancelConfirmed(String from, Merchant store, List<String> codes) {
        List<String> open = openOrdersOf(from, store).stream().map(OpenOrder::code).toList();
        List<String> done = new ArrayList<>(), failed = new ArrayList<>();
        for (String code : codes) {
            if (!open.contains(code)) {
                failed.add(code);
                continue;
            }
            try {
                orderService.cancelOrder(code, String.valueOf(store.getId()), null, "Cancelled by the customer on WhatsApp");
                awaitingPayment.remove(code);
                done.add(code);
            } catch (RuntimeException e) {
                log.warn("Cancelling order {} for {} failed: {}", code, from, e.getMessage());
                failed.add(code);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!done.isEmpty()) sb.append(done.size() == 1 ? "Order " : "Orders ").append(String.join(", ", done)).append(done.size() == 1 ? " is" : " are").append(" cancelled.");
        if (!failed.isEmpty()) sb.append(sb.isEmpty() ? "" : "\n").append("Couldn't cancel ").append(String.join(", ", failed))
                .append(" (paid, delivered or already cancelled).");
        wa.sendText(from, sb.toString());
    }

    /** The customer's most recent placed order at this store, if any. */
    private Order latestOrder(String from, Merchant store) {
        ShopCart last = carts.findFirstByPhoneAndStoreMerchantIdAndStatusInOrderByIdDesc(from, store.getId(),
                List.of(ShopCart.Status.ORDERED)).orElse(null);
        return last == null || last.getOrderCode() == null ? null
                : orderRepository.findByOrderCode(last.getOrderCode()).orElse(null);
    }

    /** Back to an empty, open cart (items, open questions and a half-picked item all gone). */
    private void emptyCart(ShopCart cart) {
        cart.setItemsJson("[]");
        cart.setQuestionsJson("[]");
        cart.setPendingItemCode(null);
        cart.setDeliveryAddress(null);
        cart.setShopName(null);
        cart.setShopMerchantId(null);
        cart.setPhotoShopName(null);
        cart.setContactPerson(null);
        cart.setPreviousItemsJson(null);
        cart.setStatus(ShopCart.Status.OPEN);
        save(cart);
    }

    private void confirm(String from, Merchant store, ShopCart cart) {
        if (items(cart).isEmpty()) {
            welcome(from, store, cart, "Your cart is empty.");
            return;
        }
        if (b2b()) {
            nextShopStep(from, store, cart);
            return;
        }
        cart.setStatus(ShopCart.Status.AWAITING_ADDRESS);
        save(cart);
        wa.sendText(from, "Where should we deliver? Type your address (estate, street, a landmark) or share your location: "
                + "tap 📎 then Location. Delivery is free.");
    }

    /** An address was typed or shared: B2B keeps it as the shop's location and goes on; consumers order now. */
    private void deliverTo(String from, ShopCart cart, Merchant store, String address) {
        if (!b2b()) {
            placeOrder(from, cart, store, address);
            return;
        }
        if (address == null || address.isBlank()) {
            wa.sendText(from, "Please type your shop's location or share it (📎 → Location).");
            return;
        }
        cart.setDeliveryAddress(address.trim());
        save(cart);
        nextShopStep(from, store, cart);
    }

    /**
     * B2B, after Confirm: the order needs a shop name, a contact person and a location. Ask for whichever is
     * still missing (a known shop already has its name, and usually its location), then place the order.
     */
    private void nextShopStep(String from, Merchant store, ShopCart cart) {
        if (items(cart).isEmpty()) {
            welcome(from, store, cart, "Your cart is empty.");
            return;
        }
        if (!isBlank(cart.getPhotoShopName()) && cart.getShopMerchantId() == null && isBlank(cart.getShopName())) {
            cart.setStatus(ShopCart.Status.AWAITING_SHOP_NAME);
            save(cart);
            String read = cart.getPhotoShopName();
            Merchant mine = knownShop(from, store);
            List<WhatsAppCloudClient.Button> buttons = new ArrayList<>();
            buttons.add(new WhatsAppCloudClient.Button("SHOPNAME_PHOTO", truncate("Yes: " + read, 20)));
            if (mine != null) buttons.add(new WhatsAppCloudClient.Button("SHOPNAME_MINE", truncate("No: " + mine.getBusinessName(), 20)));
            wa.sendButtons(from, "Your photo says this order is for \"" + read + "\". Is that right? Tap below, or type the correct shop name.", buttons);
            return;
        }
        if (askShopEachOrder && cart.getShopMerchantId() == null && isBlank(cart.getShopName())) {
            cart.setStatus(ShopCart.Status.AWAITING_SHOP_NAME);
            save(cart);
            List<WhatsAppCloudClient.Button> buttons = new ArrayList<>();
            for (Merchant m : shopsOfNumber(from, store)) {
                if (buttons.size() == 3) break;
                buttons.add(new WhatsAppCloudClient.Button("SHOPPICK:" + m.getId(), truncate(m.getBusinessName(), 20)));
            }
            String ask = "Which shop is this order for? " + (buttons.isEmpty() ? "Type the shop name." : "Tap one, or type another shop's name.");
            if (buttons.isEmpty()) wa.sendText(from, ask);
            else wa.sendButtons(from, ask, buttons);
            return;
        }
        Merchant shop = orderingShop(from, store, cart);
        String name = shop != null ? shop.getBusinessName() : cart.getShopName();
        if (isBlank(name)) {
            cart.setStatus(ShopCart.Status.AWAITING_SHOP_NAME);
            save(cart);
            wa.sendText(from, "Almost done! What's the name of your shop or business?");
            return;
        }
        String contact = !isBlank(cart.getContactPerson()) ? cart.getContactPerson() : savedContactName(shop, store);
        if (isBlank(contact)) {
            cart.setStatus(ShopCart.Status.AWAITING_CONTACT);
            save(cart);
            String me = cart.getCustomerName() == null ? null : cart.getCustomerName().trim();
            if (me != null && !me.isBlank()) {
                wa.sendButtons(from, "Who is the contact person for " + name + "? Tap below if it's you, or type their name.",
                        List.of(new WhatsAppCloudClient.Button("CONTACT_ME", truncate("It's me: " + me, 20))));
            } else {
                wa.sendText(from, "Who is the contact person for " + name + "? Type their name.");
            }
            return;
        }
        String location = !isBlank(cart.getDeliveryAddress()) ? cart.getDeliveryAddress()
                : shop != null && !isBlank(shop.getLocation()) ? shop.getLocation() : null;
        if (isBlank(location)) {
            cart.setStatus(ShopCart.Status.AWAITING_ADDRESS);
            save(cart);
            wa.sendText(from, "Where is " + name + "? Share your shop's location (tap 📎 then Location), or type it "
                    + "(street, building, a landmark).");
            return;
        }
        placeShopOrder(from, cart, store, name, contact, location);
    }

    /**
     * B2B: creates the shop the first time (under the distributor, so it shows in its CRM shops), records the
     * contact person, and places one SHOP order at wholesale prices, pay on delivery. Quantities aren't capped at
     * today's stock: the distributor sees any shortage when it fulfils the order.
     */
    private void placeShopOrder(String from, ShopCart cartRef, Merchant store, String shopName, String contact, String location) {
        List<String> notes = new ArrayList<>();
        Map<String, BigDecimal> prices = prices(store);
        String orderCode = tx.execute(status -> {
            ShopCart cart = carts.lockById(cartRef.getId()).orElseThrow();
            if (cart.getStatus() == ShopCart.Status.ORDERED && cart.getOrderCode() != null) {
                return cart.getOrderCode(); // already placed (double tap / retry): just show it again
            }
            List<OrderItemRequest> lines = new ArrayList<>();
            for (Item it : items(cart)) {
                Inventory i = liveItem(store, it.code()).orElse(null);
                BigDecimal price = priceOf(i, prices);
                if (i == null || price == null) {
                    notes.add((i == null ? "an item" : i.getItemName()) + " is no longer sold");
                    continue;
                }
                if (available(i) < it.qty()) {
                    notes.add(i.getItemName() + ": " + (available(i) <= 0 ? "out of stock now" : "only " + available(i) + " in stock now"));
                }
                OrderItemRequest line = new OrderItemRequest();
                line.setItemCode(i.getItemCode());
                line.setItemName(i.getItemName());
                line.setQuantity(it.qty());
                line.setWholesalePrice(price);
                lines.add(line);
            }
            if (lines.isEmpty()) return null;

            Merchant shop = orderingShop(from, store, cart);
            if (shop == null) shop = shopForNewNumber(from, store, shopName, location);
            contacts.setContactPerson(store.getId(), shop.getId(), contact, localPhone(from));

            OrderRequest req = new OrderRequest();
            req.setMerchantId(String.valueOf(shop.getId()));
            req.setItems(lines);
            req.setPaymentMode(PaymentMode.PAY_ON_DELIVERY);
            req.setPhoneNumber(localPhone(from));
            Order created = orderService.createOrder(String.valueOf(store.getId()), req, OrderSource.SHOP, null, null);
            created.setCustomerName(truncate(contact, 120));
            created.setDeliveryAddress(location.trim());
            created.setShopNote("Ordered on WhatsApp by " + contact
                    + (notes.isEmpty() ? "" : ". Stock to confirm: " + String.join("; ", notes)));
            created = orderRepository.save(created);
            cart.setStatus(ShopCart.Status.ORDERED);
            cart.setOrderId(created.getId());
            cart.setOrderCode(created.getOrderCode());
            cart.setDeliveryAddress(location.trim());
            cart.setUpdatedAt(LocalDateTime.now());
            carts.save(cart);
            return created.getOrderCode();
        });
        if (orderCode == null) {
            wa.sendButtons(from, "Sorry, the products in your cart are no longer sold.",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        String summary = tx.execute(status -> {
            Order order = orderRepository.findByOrderCode(orderCode).orElseThrow();
            StringBuilder sb = new StringBuilder("✅ Order " + order.getOrderCode() + " sent to " + store.getBusinessName() + "!\n");
            order.getItems().forEach(i -> sb.append(i.getQuantity()).append(" x ").append(i.getItemName())
                    .append(" — KES ").append(money(i.getLineTotal())).append('\n'));
            sb.append("\nTotal: KES ").append(money(order.getTotalAmount()))
                    .append("\nShop: ").append(shopName)
                    .append("\nContact: ").append(contact)
                    .append("\nDeliver to: ").append(order.getDeliveryAddress());
            if (!notes.isEmpty()) sb.append("\n\n⚠️ ").append(String.join("; ", notes)).append(". ")
                    .append(store.getBusinessName()).append(" will confirm what it can deliver.");
            sb.append("\n\nPay on delivery, or pay now with M-Pesa:");
            return sb.toString();
        });
        wa.sendButtons(from, summary, List.of(
                new WhatsAppCloudClient.Button("PAY:" + orderCode, "Pay now (M-Pesa)"),
                new WhatsAppCloudClient.Button("PAYLATER", "Pay on delivery")));
    }

    /**
     * A shop we don't have yet: reuse one already registered on this number with the same name, otherwise
     * register it (one phone can order for several shops, e.g. a rep or an owner with two bars).
     */
    private Merchant shopForNewNumber(String from, Merchant store, String shopName, String location) {
        String phone = localPhone(from);
        Merchant same = merchantRepository.findByBusinessPhone(phone).stream()
                .filter(m -> coreName(m.getBusinessName()).equals(coreName(shopName))).findFirst().orElse(null);
        if (same != null) return same;
        Merchant shop = Merchant.builder()
                .businessName(truncate(shopName.trim(), 255))
                .location(truncate(location.trim(), 255))
                .businessType(isBlank(store.getBusinessType()) ? "RETAIL" : store.getBusinessType())
                .businessPhone(phone)
                .distributor(store)
                .build();
        shop = merchantRepository.save(shop);
        log.info("Registered shop {} ({}) for {} from WhatsApp", shop.getId(), shop.getBusinessName(), store.getBusinessName());
        return shop;
    }

    /** Re-checks prices and stock, creates the order once, then sends the M-Pesa prompt. */
    private void placeOrder(String from, ShopCart cartRef, Merchant store, String address) {
        if (address == null || address.isBlank()) {
            wa.sendText(from, "Please type your delivery address or share your location.");
            return;
        }
        Merchant buyer = customersAccount(store).orElse(null);
        if (buyer == null) {
            wa.sendText(from, "Ordering isn't set up for this store yet. Please try again later.");
            return;
        }
        List<String> changes = new ArrayList<>();
        String orderCode = tx.execute(status -> {
            ShopCart cart = carts.lockById(cartRef.getId()).orElseThrow();
            if (cart.getStatus() == ShopCart.Status.ORDERED && cart.getOrderCode() != null) {
                return cart.getOrderCode(); // already placed (double tap / retry): just show it again
            }
            List<OrderItemRequest> lines = new ArrayList<>();
            for (Item it : items(cart)) {
                Inventory i = liveItem(store, it.code()).orElse(null);
                if (i == null || i.getUnitPrice() == null || available(i) <= 0) {
                    changes.add((i == null ? "an item" : i.getItemName()) + " is no longer available");
                    continue;
                }
                int qty = Math.min(it.qty(), available(i));
                if (qty < it.qty()) changes.add("only " + qty + " " + i.getItemName() + " left");
                OrderItemRequest line = new OrderItemRequest();
                line.setItemCode(i.getItemCode());
                line.setItemName(i.getItemName());
                line.setQuantity(qty);
                line.setWholesalePrice(i.getUnitPrice()); // the line price: the store's current retail price
                lines.add(line);
            }
            if (lines.isEmpty()) return null;
            OrderRequest req = new OrderRequest();
            req.setMerchantId(String.valueOf(buyer.getId()));
            req.setItems(lines);
            req.setPaymentMode(PaymentMode.PAY_ON_DELIVERY);
            req.setPhoneNumber(localPhone(from));
            Order created = orderService.createOrder(String.valueOf(store.getId()), req, OrderSource.CUSTOMER, null, null);
            created.setCustomerName(cart.getCustomerName());
            created.setDeliveryAddress(address.trim());
            created = orderRepository.save(created);
            cart.setStatus(ShopCart.Status.ORDERED);
            cart.setOrderId(created.getId());
            cart.setOrderCode(created.getOrderCode());
            cart.setDeliveryAddress(address.trim());
            cart.setUpdatedAt(LocalDateTime.now());
            carts.save(cart);
            return created.getOrderCode();
        });
        if (orderCode == null) {
            wa.sendButtons(from, "Sorry, the items in your cart are no longer available.",
                    List.of(new WhatsAppCloudClient.Button("BROWSE", "Browse products")));
            return;
        }
        // Built inside a transaction: the order's items are loaded lazily
        String summary = tx.execute(status -> {
            Order order = orderRepository.findByOrderCode(orderCode).orElseThrow();
            StringBuilder sb = new StringBuilder("✅ Order " + order.getOrderCode() + " placed!\n");
            if (!changes.isEmpty()) sb.append("Note: ").append(String.join("; ", changes)).append(".\n");
            order.getItems().forEach(i -> sb.append(i.getQuantity()).append(" x ").append(i.getItemName())
                    .append(" — KES ").append(money(i.getLineTotal())).append('\n'));
            sb.append("\nTotal: KES ").append(money(order.getTotalAmount()))
                    .append("\nDeliver to: ").append(order.getDeliveryAddress());
            return sb.toString();
        });
        wa.sendText(from, summary);
        pay(from, orderCode);
    }

    private void pay(String from, String orderCode) {
        Order order = orderRepository.findByOrderCode(orderCode).orElse(null);
        if (order == null || (order.getOrderSource() != OrderSource.CUSTOMER && order.getOrderSource() != OrderSource.SHOP)) {
            wa.sendText(from, "I couldn't find that order.");
            return;
        }
        if ("CANCELLED".equals(order.getStatus().name())) {
            wa.sendText(from, "Order " + orderCode + " was cancelled. Send \"hi\" to start a new order.");
            return;
        }
        if (order.getPaymentDate() != null || order.getStatus().name().equals("PAID")) {
            wa.sendText(from, "Order " + orderCode + " is already paid. Asante!");
            return;
        }
        try {
            orderService.collectDeliveryPayment(orderCode, localPhone(from));
            awaitingPayment.put(orderCode, LocalDateTime.now());
            wa.sendText(from, "📲 Check your phone and enter your M-Pesa PIN to pay KES " + money(order.getTotalAmount()) + ".");
        } catch (Exception e) {
            log.warn("M-Pesa prompt for {} failed: {}", orderCode, e.getMessage());
            wa.sendButtons(from, "We couldn't send the M-Pesa prompt just now. Tap below to try again.",
                    List.of(new WhatsAppCloudClient.Button("PAY:" + orderCode, "Pay with M-Pesa")));
        }
    }

    /** Checks M-Pesa for orders waiting on payment and tells the customer as soon as it lands. */
    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    public void checkPayments() {
        for (Map.Entry<String, LocalDateTime> e : new ArrayList<>(awaitingPayment.entrySet())) {
            String code = e.getKey();
            Order order = orderRepository.findByOrderCode(code).orElse(null);
            if (order == null) {
                awaitingPayment.remove(code);
                continue;
            }
            String to = order.getPhoneNumber();
            try {
                if (!"PAID".equals(order.getStatus().name())) orderService.checkPaymentStatus(code);
            } catch (Exception ex) {
                log.debug("Payment check for {} not done yet: {}", code, ex.getMessage());
            }
            Order now = orderRepository.findByOrderCode(code).orElse(order);
            if ("PAID".equals(now.getStatus().name())) {
                awaitingPayment.remove(code);
                wa.sendText(to, "✅ Payment received, asante! KES " + money(now.getTotalAmount())
                        + (now.getPaymentReference() != null ? " (M-Pesa " + now.getPaymentReference() + ")" : "")
                        + ".\nWe're packing order " + code + " and will message you when it's on the way.");
            } else if (e.getValue().isBefore(LocalDateTime.now().minusMinutes(PAYMENT_WAIT_MINUTES))) {
                awaitingPayment.remove(code);
                wa.sendButtons(to, "We haven't received the M-Pesa payment for order " + code + " yet.",
                        List.of(new WhatsAppCloudClient.Button("PAY:" + code, "Pay with M-Pesa")));
            }
        }
    }

    /** Message for the customer when the store marks the order dispatched or delivered. */
    public void notifyStep(Order order, String step) {
        if (order.getPhoneNumber() == null) return;
        String store = order.getDistributor() == null ? "the store" : order.getDistributor().getBusinessName();
        if ("DISPATCHED".equalsIgnoreCase(step)) {
            wa.sendText(order.getPhoneNumber(), "🚚 Your order " + order.getOrderCode() + " from " + store + " is on the way!");
        } else if ("DELIVERED".equalsIgnoreCase(step)) {
            wa.sendText(order.getPhoneNumber(), "📦 Order " + order.getOrderCode() + " has been delivered. Asante for shopping with "
                    + store + "! Send \"hi\" anytime to order again.");
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private Optional<Merchant> store() {
        if (storeId != null && !storeId.isBlank()) {
            try {
                return merchantRepository.findById(Long.valueOf(storeId.trim()));
            } catch (NumberFormatException e) {
                log.warn("whatsapp.shop.store-id is not a number: {}", storeId);
                return Optional.empty();
            }
        }
        return merchantRepository.findByBusinessNameContainingIgnoreCase(storeName).stream()
                .filter(m -> storeName.equalsIgnoreCase(m.getBusinessName())).findFirst();
    }

    private Optional<Merchant> customersAccount(Merchant store) {
        String name = store.getBusinessName() + FreshMartSeeder.CUSTOMERS_SUFFIX;
        return merchantRepository.findByBusinessNameContainingIgnoreCase(name).stream()
                .filter(m -> name.equalsIgnoreCase(m.getBusinessName())).findFirst();
    }

    /** Active, priced items of the store, in catalogue order. */
    private List<Inventory> catalogue(Merchant store) {
        return inventoryRepository.findByMerchantId(String.valueOf(store.getId())).stream()
                .filter(i -> !Boolean.FALSE.equals(i.getIsActive()) && i.getUnitPrice() != null && i.getUnitPrice().signum() > 0)
                .sorted(Comparator.comparing(Inventory::getId))
                .toList();
    }

    private Optional<Inventory> liveItem(Merchant store, String code) {
        return inventoryRepository.findByMerchantIdAndItemCode(String.valueOf(store.getId()), code)
                .filter(i -> !Boolean.FALSE.equals(i.getIsActive()) && i.getUnitPrice() != null);
    }

    static List<String> categoryNames(List<Inventory> catalogue) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        catalogue.forEach(i -> names.add(i.getProductCategory() == null ? "Other" : i.getProductCategory()));
        return new ArrayList<>(names);
    }

    private boolean b2b() {
        return "b2b".equalsIgnoreCase(kind);
    }

    private static ShoppingListReader.Vertical vertical(Merchant store) {
        return "LIQUOR".equalsIgnoreCase(store.getBusinessType()) ? ShoppingListReader.Vertical.LIQUOR : ShoppingListReader.Vertical.GROCERY;
    }

    /** B2B: the distributor's current wholesale price per item code (newest active row wins). Consumers: none. */
    private Map<String, BigDecimal> prices(Merchant store) {
        if (!b2b()) return Map.of();
        Map<String, BigDecimal> m = new HashMap<>();
        for (WholesalePriceConfig w : wholesaleRepository.findActiveByMerchant(String.valueOf(store.getId()))) {
            if (w.getItemCode() != null && w.getWholesalePrice() != null && w.getWholesalePrice().signum() > 0) {
                m.putIfAbsent(w.getItemCode(), w.getWholesalePrice());
            }
        }
        return m;
    }

    /** Wholesale price when there is one, otherwise the shelf price. */
    private static BigDecimal priceOf(Inventory i, Map<String, BigDecimal> prices) {
        if (i == null) return null;
        BigDecimal w = prices.get(i.getItemCode());
        return w != null ? w : i.getUnitPrice();
    }

    /** Consumers can only order what's on the shelf; a shop can order anything the distributor sells. */
    private boolean orderable(Inventory i) {
        return i != null && (b2b() || available(i) > 0);
    }

    /**
     * B2B: the shop this order is for. A shop matched from a photo comes first; a shop name given on this order
     * that we don't have yet means a new shop (null); otherwise the shop this number orders for.
     */
    private Merchant orderingShop(String from, Merchant store, ShopCart cart) {
        if (cart.getShopMerchantId() != null) {
            Merchant m = merchantRepository.findById(cart.getShopMerchantId()).orElse(null);
            if (m != null) return m;
        }
        if (!isBlank(cart.getShopName())) return null;
        return knownShop(from, store);
    }

    /** B2B: the shop this number orders for at this distributor, if we know it. */
    private Merchant knownShop(String from, Merchant store) {
        for (CrmContact c : contacts.findByNumber(from)) {
            if (store.getId().equals(c.getDistributorId())) {
                Merchant m = merchantRepository.findById(c.getShopMerchantId()).orElse(null);
                if (m != null) return m;
            }
        }
        return merchantRepository.findByBusinessPhone(localPhone(from)).stream()
                .filter(m -> m.getDistributor() != null && store.getId().equals(m.getDistributor().getId()))
                .findFirst().orElse(null);
    }

    private String savedContactName(Merchant shop, Merchant store) {
        if (shop == null) return null;
        return contacts.find(store.getId(), shop.getId()).map(CrmContact::getContactName).filter(n -> !isBlank(n)).orElse(null);
    }

    /** Items and quantities of the shop's latest order from this distributor (cancelled ones skipped). */
    private List<Item> lastOrderItems(Merchant shop, Merchant store) {
        if (shop == null) return List.of();
        List<Item> items = tx.execute(status -> orderRepository.findByMerchantIdOrderByOrderDateDesc(shop.getId()).stream()
                .filter(o -> o.getDistributor() != null && store.getId().equals(o.getDistributor().getId())
                        && !"CANCELLED".equals(String.valueOf(o.getStatus())))
                .findFirst()
                .map(o -> o.getItems().stream()
                        .filter(i -> i.getItemCode() != null && i.getQuantity() != null && i.getQuantity() > 0)
                        .map(i -> new Item(i.getItemCode(), Math.min(i.getQuantity(), MAX_QTY)))
                        .toList())
                .orElse(List.of()));
        return items == null ? List.of() : items;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int available(Inventory i) {
        return i == null || i.getAvailableStock() == null ? 0 : i.getAvailableStock();
    }

    private ShopCart openCart(String phone, Long storeId, String profileName) {
        ShopCart cart = carts.findFirstByPhoneAndStoreMerchantIdAndStatusInOrderByIdDesc(phone, storeId,
                        List.of(ShopCart.Status.OPEN, ShopCart.Status.AWAITING_ADDRESS,
                                ShopCart.Status.AWAITING_SHOP_NAME, ShopCart.Status.AWAITING_CONTACT))
                .orElseGet(() -> carts.save(ShopCart.builder().phone(phone).storeMerchantId(storeId).build()));
        if (cart.getUpdatedAt() != null && cart.getUpdatedAt().isBefore(LocalDateTime.now().minusHours(CART_EXPIRY_HOURS))
                && (!items(cart).isEmpty() || !questions(cart).isEmpty() || cart.getStatus() != ShopCart.Status.OPEN)) {
            // An abandoned cart: don't carry yesterday's items into today's order
            log.info("Emptying an abandoned WhatsApp cart {} (last used {})", cart.getId(), cart.getUpdatedAt());
            emptyCart(cart);
        }
        if (profileName != null && !profileName.isBlank() && !profileName.equals(cart.getCustomerName())) {
            cart.setCustomerName(profileName.length() > 120 ? profileName.substring(0, 120) : profileName);
            save(cart);
        }
        return cart;
    }

    private void save(ShopCart cart) {
        cart.setUpdatedAt(LocalDateTime.now());
        carts.save(cart);
    }

    List<Item> items(ShopCart cart) {
        try {
            return json.readValue(Objects.toString(cart.getItemsJson(), "[]"), new TypeReference<List<Item>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    void addItem(ShopCart cart, String code, int qty) {
        List<Item> items = new ArrayList<>(items(cart));
        int existing = items.stream().filter(i -> i.code().equals(code)).mapToInt(Item::qty).sum();
        items.removeIf(i -> i.code().equals(code));
        items.add(new Item(code, Math.min(existing + clampQty(qty), MAX_QTY)));
        try {
            cart.setItemsJson(json.writeValueAsString(items));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (cart.getStatus() == ShopCart.Status.AWAITING_ADDRESS) cart.setStatus(ShopCart.Status.OPEN);
    }

    private List<Question> questions(ShopCart cart) {
        try {
            return json.readValue(Objects.toString(cart.getQuestionsJson(), "[]"), new TypeReference<List<Question>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void setQuestions(ShopCart cart, List<Question> qs) {
        try {
            cart.setQuestionsJson(json.writeValueAsString(qs));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Pattern PACK_SIZE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(kg|g|ml|l|litres?|liters?|ltrs?)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Packs needed when the customer asked for a total ("sugar 3kg"): the total divided by the product's own
     * pack size, rounded to the nearest whole pack (at least 1). Falls back to the count Claude gave.
     */
    static int packsFor(Inventory item, Double total, String unit, int fallback) {
        Double wanted = toBase(total, unit);
        Double pack = null;
        Matcher m = item == null || item.getItemName() == null ? null : PACK_SIZE.matcher(item.getItemName());
        if (m != null && m.find()) pack = toBase(Double.parseDouble(m.group(1)), m.group(2));
        if (wanted == null || pack == null || pack <= 0 || !sameKind(unit, m.group(2))) return clampQty(fallback);
        return clampQty((int) Math.max(1, Math.round(wanted / pack)));
    }

    /** kg/g → grams, l/ml → millilitres. */
    private static Double toBase(Double amount, String unit) {
        if (amount == null || unit == null || amount <= 0) return null;
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.equals("kg")) return amount * 1000;
        if (u.equals("g")) return amount;
        if (u.equals("ml")) return amount;
        if (u.startsWith("l")) return amount * 1000;
        return null;
    }

    private static boolean sameKind(String a, String b) {
        boolean aWeight = a != null && a.toLowerCase(Locale.ROOT).matches("kg|g");
        boolean bWeight = b != null && b.toLowerCase(Locale.ROOT).matches("kg|g");
        return aWeight == bWeight;
    }

    private static String trim(Double d) {
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    static Integer quantity(String text) {
        Matcher m = NUMBER.matcher(text);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            return n > 0 && n <= MAX_QTY ? n : null;
        }
        return NUMBER_WORDS.get(text.trim());
    }

    private static int clampQty(Integer q) {
        return q == null || q < 1 ? 1 : Math.min(q, MAX_QTY);
    }

    /**
     * "Place name · map link". WhatsApp only names the place when the customer picks one from the list; a
     * "current location" pin has just coordinates, so the name is looked up. Without a name it's "Pinned location".
     */
    @SuppressWarnings("unchecked")
    private String locationText(Map<String, Object> message) {
        Object loc = message == null ? null : message.get("location");
        if (!(loc instanceof Map<?, ?> l)) return null;
        Map<String, Object> m = (Map<String, Object>) l;
        Double lat = coordinate(m.get("latitude")), lon = coordinate(m.get("longitude"));
        if (lat == null || lon == null) return null;
        String place = String.join(", ", Arrays.stream(new Object[]{m.get("name"), m.get("address")})
                .filter(Objects::nonNull).map(Object::toString).map(String::trim).filter(s -> !s.isBlank()).toList());
        if (place.isBlank()) place = Objects.requireNonNullElse(placeNamer.name(lat, lon), "Pinned location");
        return place + " · https://maps.google.com/?q=" + lat + "," + lon;
    }

    private static Double coordinate(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return v == null ? null : Double.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2547XXXXXXXX → 07XXXXXXXX (what the order and M-Pesa code expect). */
    static String localPhone(String wa) {
        String d = wa == null ? "" : wa.replaceAll("\\D", "");
        return d.startsWith("254") && d.length() == 12 ? "0" + d.substring(3) : d;
    }

    /** List rows cut titles at 24 characters: repeat a long name in the description with the price. */
    static String rowDescription(Inventory i) {
        return rowDescription(i, i.getUnitPrice());
    }

    static String rowDescription(Inventory i, BigDecimal unitPrice) {
        String price = "KES " + money(unitPrice);
        return i.getItemName().length() > 24 ? price + " · " + i.getItemName() : price;
    }

    static String money(BigDecimal v) {
        return NumberFormat.getIntegerInstance(Locale.US).format((v == null ? BigDecimal.ZERO : v).setScale(0, RoundingMode.HALF_UP));
    }
}
