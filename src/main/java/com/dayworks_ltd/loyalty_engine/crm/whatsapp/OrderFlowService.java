package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderLinkService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the WhatsApp order form (Flow) endpoint. The flow_token is the shop's one-time order
 * token, so the form follows the same rules as the web order page: one order per token, 3-day
 * expiry, prices from the distributor's catalogue, pay on delivery.
 *
 * Screens (see resources/whatsapp/order-flow.json): ORDER → SUMMARY → DONE, or CLOSED when the
 * token is used up or expired.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFlowService {

    /** Item rows on the ORDER screen — must match order-flow.json. */
    static final int ROWS = 8;
    /** WhatsApp dropdowns take at most 200 options. */
    private static final int MAX_CATALOG = 200;
    private static final Pattern LEADING_QTY = Pattern.compile("^(\\d{1,4})\\s*(?:x\\s*)?(.+)$", Pattern.CASE_INSENSITIVE);

    private final ShopOrderService shopOrderService;
    private final ShopOrderLinkService linkService;
    private final CrmContactService contactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) {
        String action = String.valueOf(request.get("action"));
        Map<String, Object> data = request.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();

        if ("ping".equals(action)) return Map.of("data", Map.of("status", "active"));
        if (data.containsKey("error") && !"INIT".equals(action)) {
            log.warn("WhatsApp flow client error: {}", data);
            return Map.of("data", Map.of("acknowledged", true));
        }
        String token = request.get("flow_token") == null ? null : request.get("flow_token").toString();

        try {
            if ("INIT".equals(action) || "BACK".equals(action)) return orderScreen(token, Map.of(), null);
            if ("data_exchange".equals(action)) {
                String step = String.valueOf(data.get("action"));
                if ("review".equals(step)) return review(token, data);
                if ("place".equals(step)) return place(token, String.valueOf(data.get("cart")));
            }
            return closed("Something went wrong. Send ORDER on chat to get a new order form.");
        } catch (NoSuchElementException | IllegalStateException e) {
            return closed(e.getMessage() + ". Send ORDER on chat to get a new order form.");
        }
    }

    // ───────────────────────────── screens ─────────────────────────────

    /** ORDER screen; typed values (when re-showing after an error) win over the usual order. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> orderScreen(String token, Map<String, Object> typed, String error) {
        Map<String, Object> view = shopOrderService.view(token, typed.isEmpty() && error == null);
        if ("USED".equals(view.get("status"))) {
            Map<String, Object> order = (Map<String, Object>) view.get("order");
            String code = order == null ? "" : " (" + order.get("orderCode") + ")";
            return closed("You already placed an order" + code + " with this form. Send ORDER on chat to order again.");
        }

        List<Map<String, Object>> catalog = ((List<Map<String, Object>>) view.getOrDefault("catalog", List.of())).stream()
                .filter(c -> Boolean.TRUE.equals(c.get("inStock")))
                .limit(MAX_CATALOG)
                .toList();
        Set<String> codes = new HashSet<>();
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map<String, Object> c : catalog) {
            codes.add(String.valueOf(c.get("itemCode")));
            options.add(Map.of(
                    "id", String.valueOf(c.get("itemCode")),
                    "title", limit(String.valueOf(c.get("itemName")), 30),
                    "description", "KES " + money(c.get("price"))));
        }

        String shop = String.valueOf(view.getOrDefault("shopName", ""));
        String distributor = String.valueOf(view.getOrDefault("distributorName", ""));
        List<Map<String, Object>> usual = ((List<Map<String, Object>>) view.getOrDefault("usual", List.of())).stream()
                .filter(u -> codes.contains(String.valueOf(u.get("itemCode"))))
                .limit(ROWS)
                .toList();

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("heading", "Order from " + distributor);
        d.put("intro", (usual.isEmpty() || !typed.isEmpty())
                ? "Hi " + shop + ", pick the items and quantities you need. Leave rows you don't need empty."
                : "Hi " + shop + ", your usual order is filled in - change anything you like. Leave rows you don't need empty.");
        d.put("catalog", options);
        for (int n = 1; n <= ROWS; n++) {
            String item = "";
            String qty = "";
            if (!typed.isEmpty()) {
                item = str(typed.get("item_" + n));
                qty = str(typed.get("qty_" + n));
                if (!codes.contains(item)) item = "";
            } else if (n <= usual.size()) {
                item = String.valueOf(usual.get(n - 1).get("itemCode"));
                qty = String.valueOf(usual.get(n - 1).get("quantity"));
            }
            d.put("item_" + n, item);
            d.put("qty_" + n, qty);
        }
        d.put("extras", typed.isEmpty() ? "" : str(typed.get("extras")));
        if (error != null) d.put("error_message", error);
        return screen("ORDER", d);
    }

    private Map<String, Object> review(String token, Map<String, Object> data) {
        List<ShopOrderService.ItemQty> items = new ArrayList<>();
        for (int n = 1; n <= ROWS; n++) {
            String code = str(data.get("item_" + n));
            String qtyText = str(data.get("qty_" + n));
            if (code.isEmpty() && qtyText.isEmpty()) continue;
            if (code.isEmpty()) return orderScreen(token, data, "Row " + n + " has a quantity but no item");
            Integer qty = parseQty(qtyText);
            if (qty == null) return orderScreen(token, data, "Enter a quantity for item " + n);
            items.add(new ShopOrderService.ItemQty(code, qty));
        }
        List<ShopOrderService.Extra> extras = parseExtras(str(data.get("extras")));
        if (items.isEmpty() && extras.isEmpty()) return orderScreen(token, data, "Pick at least one item");

        ShopOrderService.Quote quote;
        try {
            quote = shopOrderService.quote(token, items);
        } catch (IllegalArgumentException e) {
            return orderScreen(token, data, e.getMessage());
        }

        Map<String, Object> cart = new LinkedHashMap<>();
        cart.put("items", quote.lines().stream().map(l -> Map.of("c", l.itemCode(), "q", l.quantity())).toList());
        cart.put("extras", extras.stream().map(e -> Map.of("n", e.name(), "q", e.quantity())).toList());
        try {
            return screen("SUMMARY", Map.of("summary", summaryText(quote, extras), "cart", objectMapper.writeValueAsString(cart)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare your order", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> place(String token, String cartJson) {
        List<ShopOrderService.ItemQty> items = new ArrayList<>();
        List<ShopOrderService.Extra> extras = new ArrayList<>();
        try {
            Map<String, Object> cart = objectMapper.readValue(cartJson, new TypeReference<>() {});
            for (Map<String, Object> i : (List<Map<String, Object>>) cart.getOrDefault("items", List.of())) {
                items.add(new ShopOrderService.ItemQty(str(i.get("c")), ((Number) i.get("q")).intValue()));
            }
            for (Map<String, Object> e : (List<Map<String, Object>>) cart.getOrDefault("extras", List.of())) {
                extras.add(new ShopOrderService.Extra(str(e.get("n")), ((Number) e.get("q")).intValue()));
            }
        } catch (Exception e) {
            return closed("Your order could not be read. Send ORDER on chat to try again.");
        }

        ShopOrderLink link = linkService.find(token).orElseThrow(() -> new NoSuchElementException("This order form is not valid"));
        CrmContact contact = contactService.getOrCreate(link.getDistributorId(), link.getShopMerchantId());
        Map<String, Object> order;
        try {
            order = shopOrderService.placeOrder(token, items, extras, contact.getWhatsappNumber());
        } catch (IllegalArgumentException e) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("summary", e.getMessage() + ". Tap back to change your order.");
            d.put("cart", cartJson);
            d.put("error_message", e.getMessage());
            return screen("SUMMARY", d);
        }

        BigDecimal total = order.get("totalKes") instanceof BigDecimal b ? b : BigDecimal.ZERO;
        String code = String.valueOf(order.get("orderCode"));
        String message = "Order " + code + " is with your distributor."
                + (total.signum() > 0 ? " Total KES " + money(total) + " - pay on delivery." : "")
                + (extras.isEmpty() ? "" : " They will confirm prices for the extra items.")
                + " Tap Done and we will send you an M-Pesa option on chat.";
        return screen("DONE", Map.of("message", message, "order_code", code));
    }

    private Map<String, Object> closed(String message) {
        return screen("CLOSED", Map.of("message", message));
    }

    private static Map<String, Object> screen(String id, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("screen", id);
        m.put("data", data);
        return m;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    static String summaryText(ShopOrderService.Quote quote, List<ShopOrderService.Extra> extras) {
        StringBuilder sb = new StringBuilder();
        for (ShopOrderService.QuoteLine l : quote.lines()) {
            sb.append(l.quantity()).append(" x ").append(l.itemName())
                    .append(" - KES ").append(money(l.lineTotal())).append('\n');
        }
        if (!quote.lines().isEmpty()) sb.append("Total: KES ").append(money(quote.total()));
        if (!extras.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Also asking for (price to be confirmed):");
            extras.forEach(e -> sb.append('\n').append(e.quantity()).append(" x ").append(e.name()));
        }
        return sb.toString();
    }

    /** "2 Guinness 500ml, Kenya Cane 750ml" → [2 x Guinness 500ml, 1 x Kenya Cane 750ml]. */
    static List<ShopOrderService.Extra> parseExtras(String text) {
        List<ShopOrderService.Extra> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[,;\\n]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            Matcher m = LEADING_QTY.matcher(p);
            if (m.matches()) out.add(new ShopOrderService.Extra(m.group(2).trim(), Integer.parseInt(m.group(1))));
            else out.add(new ShopOrderService.Extra(p, 1));
        }
        return out;
    }

    private static Integer parseQty(String text) {
        try {
            int q = (int) Math.round(Double.parseDouble(text.trim()));
            return q > 0 ? q : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String money(Object amount) {
        BigDecimal v = amount instanceof BigDecimal b ? b
                : amount instanceof Number n ? BigDecimal.valueOf(n.doubleValue())
                : BigDecimal.ZERO;
        return NumberFormat.getIntegerInstance(Locale.US).format(v.setScale(0, RoundingMode.HALF_UP));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
