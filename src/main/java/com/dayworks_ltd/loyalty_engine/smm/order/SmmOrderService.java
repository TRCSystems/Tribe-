package com.dayworks_ltd.loyalty_engine.smm.order;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderItemRequest;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderRequest;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderSource;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * The SokoReply order page a buyer opens from a comment reply: what is on the Reel, what it costs, where it
 * delivers, and how to pay. Reuses the ordinary consumer order path ({@link OrderService}) the WhatsApp shop
 * assistant already uses, with {@link OrderSource#SOCIAL} and the Reel/comment recorded on the order.
 * <p>
 * Payment is always the seller's own verified till (see {@link com.dayworks_ltd.loyalty_engine.smm.intake.SmmTillCheckService}),
 * never Tribe's shortcode: the page shows "Pay via M-Pesa — Till N · NAME" and the buyer reports having paid, since
 * most sellers have no Daraja/STK of their own for Tribe to push into.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmOrderService {

    /** sent to the seller when a buyer places an order; needs Meta's approval like sokoreply_hot_lead/morning_summary. */
    public static final String NEW_ORDER_TEMPLATE = "sokoreply_new_order";

    private final SmmOrderLinkService linkService;
    private final SmmPostItemRepository postItems;
    private final SmmSetRepository sets;
    private final SmmDeliveryZoneRepository zones;
    private final SmmSellerProfileRepository profiles;
    private final SmmAccountRepository accounts;
    private final InventoryRepository inventory;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final WhatsAppCloudClient whatsapp;

    public record ItemLine(String itemCode, int quantity) {}

    public record ViewItem(String itemCode, String name, String colour, String size, BigDecimal price, Integer stock) {}

    public record ViewSet(Long id, String name, BigDecimal price, List<String> items, List<String> optionalItems) {}

    public record ViewZone(Long id, String name, BigDecimal fee, String days) {}

    public record View(String sellerName, String location, String landmark, boolean tillVerified, String tillNumber,
                       String tillName, List<ViewItem> items, List<ViewSet> sets, List<ViewZone> zones) {}

    public record PlacedOrder(String orderCode, BigDecimal totalAmount, boolean tillVerified, String tillNumber, String tillName) {}

    /** Catalogue and payment info for the order page. */
    public View view(String token) {
        SmmOrderLink link = linkService.find(token)
                .orElseThrow(() -> new NoSuchElementException("This order link has expired or doesn't exist."));
        linkService.recordOpen(link);
        return viewFor(link);
    }

    private View viewFor(SmmOrderLink link) {
        SmmSellerProfile profile = profiles.findByMerchantId(link.getMerchantId())
                .orElseThrow(() -> new NoSuchElementException("This seller is no longer on SokoReply."));
        SmmAccount account = accounts.findByMerchantId(link.getMerchantId()).stream().findFirst().orElse(null);
        String sellerName = account == null || account.getPageName() == null ? "This shop" : account.getPageName().replaceFirst("^@", "");

        List<ViewItem> items = itemsOnPost(link.getMerchantId(), link.getPostId());
        List<ViewSet> setViews = sets.findByMerchantIdAndActiveTrue(link.getMerchantId()).stream()
                .map(s -> new ViewSet(s.getId(), s.getName(), s.getSetPrice(), s.items(), s.optionalItems())).toList();
        List<ViewZone> zoneViews = zones.findByMerchantIdAndActiveTrue(link.getMerchantId()).stream()
                .map(z -> new ViewZone(z.getId(), z.getName(), z.getFee(), z.getDays())).toList();

        boolean verified = tillVerified(profile);
        return new View(sellerName, profile.getLocationText(), profile.getLandmarkDirections(), verified,
                verified ? profile.getTillNumber() : null, verified ? profile.getTillName() : null, items, setViews, zoneViews);
    }

    /** Same test as {@code SmmWatermarkService.canWatermark}: only a till M-Pesa itself has confirmed gets shown. */
    static boolean tillVerified(SmmSellerProfile p) {
        return p.getTillNumber() != null && !p.getTillNumber().isBlank()
                && p.getTillName() != null && !p.getTillName().isBlank()
                && p.getTillVerifiedAt() != null;
    }

    private List<ViewItem> itemsOnPost(Long merchantId, String postId) {
        Set<String> codes = postItems.findByMerchantIdAndPostId(merchantId, postId).stream()
                .map(SmmPostItem::getItemCode).collect(java.util.stream.Collectors.toSet());
        if (codes.isEmpty()) return List.of();
        List<ViewItem> out = new ArrayList<>();
        for (Inventory i : inventory.findByMerchantId(String.valueOf(merchantId))) {
            String product = i.getParentCode() != null ? i.getParentCode() : i.getItemCode();
            if (!codes.contains(product) && !codes.contains(i.getItemCode())) continue;
            out.add(new ViewItem(i.getItemCode(), i.getItemName(), i.getColour(), i.getSize(), i.getUnitPrice(),
                    i.isService() ? null : i.getAvailableStock()));
        }
        return out;
    }

    /**
     * Places the order (buyer pays the seller's own till, reported separately — see {@link #reportPaid}).
     * {@code commentId} is the specific comment that brought this buyer here, if known; the link itself is shared
     * by every commenter on the post, so this is the only place that attribution to one comment is recorded.
     */
    @Transactional
    public PlacedOrder placeOrder(String token, List<ItemLine> itemLines, Long zoneId, String phone, String customerName, String commentId) {
        if (itemLines == null || itemLines.isEmpty()) throw new IllegalArgumentException("Choose at least one item.");
        if (phone == null || phone.replaceAll("\\D", "").length() < 9) throw new IllegalArgumentException("Give a phone number we can reach you on.");

        SmmOrderLink link = linkService.find(token)
                .orElseThrow(() -> new NoSuchElementException("This order link has expired or doesn't exist."));
        SmmSellerProfile profile = profiles.findByMerchantId(link.getMerchantId())
                .orElseThrow(() -> new NoSuchElementException("This seller is no longer on SokoReply."));
        Map<String, Inventory> byCode = new HashMap<>();
        for (Inventory i : inventory.findByMerchantId(String.valueOf(link.getMerchantId()))) byCode.put(i.getItemCode(), i);

        List<OrderItemRequest> orderItems = new ArrayList<>();
        for (ItemLine line : itemLines) {
            Inventory inv = byCode.get(line.itemCode());
            if (inv == null) throw new IllegalArgumentException("That item isn't available: " + line.itemCode());
            if (!inv.isService() && inv.getAvailableStock() != null && inv.getAvailableStock() < line.quantity())
                throw new IllegalArgumentException(inv.getItemName() + " only has " + inv.getAvailableStock() + " left.");
            if (line.quantity() < 1) throw new IllegalArgumentException("Quantity must be at least 1.");
            OrderItemRequest req = new OrderItemRequest();
            req.setItemCode(inv.getItemCode());
            req.setItemName(inv.getItemName());
            req.setQuantity(line.quantity());
            req.setWholesalePrice(inv.getUnitPrice());
            orderItems.add(req);
        }

        SmmDeliveryZone zone = zoneId == null ? null : zones.findByMerchantIdAndActiveTrue(link.getMerchantId()).stream()
                .filter(z -> z.getId().equals(zoneId)).findFirst().orElse(null);
        if (zone != null && zone.getFee() != null && zone.getFee().signum() > 0) {
            OrderItemRequest fee = new OrderItemRequest();
            fee.setItemCode("DELIVERY");
            fee.setItemName("Delivery to " + zone.getName());
            fee.setQuantity(1);
            fee.setWholesalePrice(zone.getFee());
            orderItems.add(fee);
        }

        OrderRequest req = new OrderRequest();
        req.setMerchantId(String.valueOf(link.getMerchantId()));
        req.setItems(orderItems);
        req.setPaymentMode(PaymentMode.SELLER_TILL);
        req.setPhoneNumber(phone);

        Order created = orderService.createOrder(String.valueOf(link.getMerchantId()), req, OrderSource.SOCIAL, null, null);
        created.setSocialPostId(link.getPostId());
        created.setSocialCommentId(commentId);
        if (customerName != null && !customerName.isBlank()) created.setCustomerName(customerName.trim());
        orders.save(created);
        linkService.recordOrder(link);

        boolean verified = tillVerified(profile);
        alertSellerOfNewOrder(profile, created, orderItems);
        return new PlacedOrder(created.getOrderCode(), created.getTotalAmount(), verified,
                verified ? profile.getTillNumber() : null, verified ? profile.getTillName() : null);
    }

    /** The buyer taps "I've paid"; the seller confirms on their till side before fulfilling. */
    @Transactional
    public void reportPaid(String orderCode, String mpesaCode) {
        Order order = orders.findByOrderCode(orderCode)
                .orElseThrow(() -> new NoSuchElementException("Order not found."));
        if (order.getOrderSource() != OrderSource.SOCIAL) throw new IllegalArgumentException("Not a SokoReply order.");
        order.setStatus(OrderStatus.PAID);
        if (mpesaCode != null && !mpesaCode.isBlank()) order.setPaymentReference(mpesaCode.trim().toUpperCase(Locale.ROOT));
        order.setPaymentDate(java.time.LocalDateTime.now());
        orders.save(order);

        SmmSellerProfile profile = profiles.findByMerchantId(order.getMerchant().getId()).orElse(null);
        if (profile != null && profile.getWhatsappNumber() != null) {
            whatsapp.sendText(profile.getWhatsappNumber(), "💰 Buyer says they've paid for order " + order.getOrderCode()
                    + " (KES " + order.getTotalAmount() + (mpesaCode == null || mpesaCode.isBlank() ? "" : ", code " + order.getPaymentReference())
                    + "). Please check your till and get it ready for " + (order.getCustomerName() == null ? "them" : order.getCustomerName()) + ".");
        }
    }

    private void alertSellerOfNewOrder(SmmSellerProfile profile, Order order, List<OrderItemRequest> items) {
        if (profile.getWhatsappNumber() == null || profile.getWhatsappNumber().isBlank()) return;
        String firstItem = items.isEmpty() ? "an item" : items.get(0).getItemName();
        WhatsAppCloudClient.Sent sent = whatsapp.sendTemplate(profile.getWhatsappNumber(), NEW_ORDER_TEMPLATE,
                List.of(order.getOrderCode(), firstItem, order.getTotalAmount().toPlainString()), null);
        if (!sent.ok()) log.warn("SokoReply: new-order alert for merchant {} failed: {}", profile.getMerchantId(), sent.error());
    }

    /** The Meta template to register (utility), matching {@link #alertSellerOfNewOrder}. */
    public static Map<String, Object> templateDefinition(String language) {
        String text = "🛒 Tribe: new order {{1}} from your Reel — {{2}}, total KES {{3}}. Open Tribe to see the full order "
                + "and delivery details: https://tribessystems.co.ke";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", text);
        body.put("example", Map.of("body_text", List.of(List.of("SR-1234", "White suitcase 20kg", "1,500.00"))));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", NEW_ORDER_TEMPLATE);
        def.put("language", language);
        def.put("category", "UTILITY");
        def.put("components", List.of(body));
        return def;
    }
}
