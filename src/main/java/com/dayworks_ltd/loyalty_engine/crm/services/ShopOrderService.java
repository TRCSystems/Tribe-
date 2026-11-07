package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats;
import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.WholesaleCatalogDto;
import com.dayworks_ltd.loyalty_engine.inventory.services.InventoryService;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderItemRequest;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderRequest;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderSource;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The shop side of a one-time ordering link: show the distributor's catalogue (prices, in/out of stock,
 * no quantities) with the shop's usual order pre-filled, place a pay-on-delivery order, and optionally
 * send an M-Pesa prompt to pay straight away.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopOrderService {

    private static final int MAX_QTY = 1000;
    private static final int MAX_EXTRAS = 20;

    private final ShopOrderLinkService linkService;
    private final InventoryService inventoryService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final MerchantService merchantService;
    private final CrmStatsService statsService;
    private final CrmContactService contactService;
    private final ReportRecipientService recipients;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record ItemQty(String itemCode, Integer quantity) {}

    public record Extra(String name, Integer quantity) {}

    /** Everything the order page needs. Throws IllegalStateException when the link is expired. */
    @Transactional
    public Map<String, Object> view(String token) {
        return view(token, true);
    }

    /** @param countOpen false for repeat reads (e.g. the WhatsApp form's review step) that aren't new opens */
    @Transactional
    public Map<String, Object> view(String token, boolean countOpen) {
        ShopOrderLink link = linkService.find(token)
                .orElseThrow(() -> new NoSuchElementException("This order link is not valid"));
        if (link.getUsedAt() == null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("This order link has expired. Ask your rep or wait for the next message.");
        }
        if (countOpen) {
            if (link.getFirstOpenedAt() == null) link.setFirstOpenedAt(LocalDateTime.now());
            link.setOpenCount(link.getOpenCount() + 1);
            linkService.save(link);
        }

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("shopName", name(link.getShopMerchantId()));
        v.put("distributorName", name(link.getDistributorId()));
        v.put("expiresAt", link.getExpiresAt());

        if (link.getUsedAt() != null) {
            v.put("status", "USED");
            v.put("order", orderRepository.findById(link.getOrderId()).map(this::orderView).orElse(null));
            return v;
        }
        v.put("status", "OPEN");
        v.put("catalog", catalog(link.getDistributorId()).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemCode", c.getItemCode());
            m.put("itemName", c.getItemName());
            m.put("price", c.getWholesalePrice());
            m.put("inStock", !"OUT_OF_STOCK".equals(c.getAvailability()));
            m.put("category", c.getProductCategory());
            m.put("imageUrl", c.getProductImageUrl());
            return m;
        }).toList());
        v.put("usual", usual(link.getDistributorId(), link.getShopMerchantId()));
        v.put("phone", contactService.getOrCreate(link.getDistributorId(), link.getShopMerchantId()).getPhone());
        return v;
    }

    /** Places the shop's order (pay on delivery). The link is single-use: it's done after this. */
    @Transactional
    public Map<String, Object> placeOrder(String token, List<ItemQty> items, List<Extra> extras, String phoneFromPage) {
        ShopOrderLink link = linkService.lock(token)
                .orElseThrow(() -> new NoSuchElementException("This order link is not valid"));
        if (link.getUsedAt() != null) {
            throw new IllegalStateException("An order has already been placed with this link");
        }
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("This order link has expired");
        }

        Map<String, WholesaleCatalogDto> catalog = catalog(link.getDistributorId()).stream()
                .collect(Collectors.toMap(WholesaleCatalogDto::getItemCode, c -> c, (a, b) -> a));

        List<OrderItemRequest> lines = new ArrayList<>();
        for (ItemQty iq : items == null ? List.<ItemQty>of() : items) {
            if (iq == null || iq.quantity() == null || iq.quantity() <= 0) continue;
            if (iq.quantity() > MAX_QTY) throw new IllegalArgumentException("Quantity too large");
            WholesaleCatalogDto c = catalog.get(iq.itemCode());
            if (c == null) throw new IllegalArgumentException("An item in your order is no longer available");
            if ("OUT_OF_STOCK".equals(c.getAvailability())) {
                throw new IllegalArgumentException(c.getItemName() + " is out of stock right now");
            }
            OrderItemRequest line = new OrderItemRequest();
            line.setItemCode(c.getItemCode());
            line.setItemName(c.getItemName());
            line.setQuantity(iq.quantity());
            line.setWholesalePrice(c.getWholesalePrice()); // price from the distributor's config, never from the page
            lines.add(line);
        }

        List<String> extraLines = new ArrayList<>();
        for (Extra e : extras == null ? List.<Extra>of() : extras) {
            if (e == null || e.name() == null || e.name().isBlank()) continue;
            if (extraLines.size() >= MAX_EXTRAS) break;
            int qty = e.quantity() == null || e.quantity() <= 0 ? 1 : Math.min(e.quantity(), MAX_QTY);
            String clean = e.name().replaceAll("[\\r\\n\\t]", " ").trim();
            extraLines.add(qty + " x " + (clean.length() > 80 ? clean.substring(0, 80) : clean));
        }
        if (lines.isEmpty() && extraLines.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item to your order");
        }

        // Phone on file; if the shop has none, the page asks for one (and we keep it for next time)
        String phone = contactService.getOrCreate(link.getDistributorId(), link.getShopMerchantId()).getPhone();
        if (phone == null) {
            if (phoneFromPage == null || phoneFromPage.isBlank()) {
                throw new IllegalArgumentException("Enter your phone number so we can confirm your order");
            }
            phone = ReportRecipientService.normalizePhone(phoneFromPage);
            contactService.update(link.getDistributorId(), link.getShopMerchantId(), phone, null, null, null);
        }
        OrderRequest req = new OrderRequest();
        req.setMerchantId(String.valueOf(link.getShopMerchantId()));
        req.setItems(lines);
        req.setPaymentMode(PaymentMode.PAY_ON_DELIVERY);
        req.setPhoneNumber(phone);

        Order order = orderService.createOrder(String.valueOf(link.getDistributorId()), req,
                OrderSource.SHOP, null, link.getCrmMessageId());
        if (!extraLines.isEmpty()) {
            order.setShopNote("Also requested (not in catalogue): " + String.join("; ", extraLines));
            order = orderRepository.save(order);
        }

        link.setUsedAt(LocalDateTime.now());
        link.setOrderId(order.getId());
        linkService.save(link);

        notifyDistributor(link, order, extraLines.size());
        return orderView(order);
    }

    public record QuoteLine(String itemCode, String itemName, int quantity, BigDecimal price, BigDecimal lineTotal) {}

    public record Quote(List<QuoteLine> lines, BigDecimal total) {}

    /**
     * Prices a basket without placing it (the WhatsApp form's review step). Same rules as
     * placeOrder: prices from the distributor's catalogue, out-of-stock and unknown items rejected,
     * repeated items merged.
     */
    public Quote quote(String token, List<ItemQty> items) {
        ShopOrderLink link = linkService.find(token)
                .orElseThrow(() -> new NoSuchElementException("This order link is not valid"));
        if (link.getUsedAt() != null) throw new IllegalStateException("An order has already been placed with this link");
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) throw new IllegalStateException("This order link has expired");

        Map<String, WholesaleCatalogDto> catalog = catalog(link.getDistributorId()).stream()
                .collect(Collectors.toMap(WholesaleCatalogDto::getItemCode, c -> c, (a, b) -> a));
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (ItemQty iq : items == null ? List.<ItemQty>of() : items) {
            if (iq == null || iq.itemCode() == null || iq.quantity() == null || iq.quantity() <= 0) continue;
            merged.merge(iq.itemCode(), iq.quantity(), Integer::sum);
        }
        List<QuoteLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            if (e.getValue() > MAX_QTY) throw new IllegalArgumentException("Quantity too large");
            WholesaleCatalogDto c = catalog.get(e.getKey());
            if (c == null) throw new IllegalArgumentException("An item in your order is no longer available");
            if ("OUT_OF_STOCK".equals(c.getAvailability())) {
                throw new IllegalArgumentException(c.getItemName() + " is out of stock right now");
            }
            BigDecimal lineTotal = c.getWholesalePrice().multiply(BigDecimal.valueOf(e.getValue()));
            lines.add(new QuoteLine(c.getItemCode(), c.getItemName(), e.getValue(), c.getWholesalePrice(), lineTotal));
            total = total.add(lineTotal);
        }
        return new Quote(lines, total);
    }

    /** Sends an M-Pesa prompt for the order placed through this link. */
    @Transactional
    public Map<String, Object> pay(String token, String phoneOverride) {
        ShopOrderLink link = linkService.find(token)
                .orElseThrow(() -> new NoSuchElementException("This order link is not valid"));
        if (link.getOrderId() == null) throw new IllegalStateException("Place your order first");
        Order order = orderRepository.findById(link.getOrderId())
                .orElseThrow(() -> new NoSuchElementException("Order not found"));
        if (order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0) {
            throw new IllegalStateException("Your distributor will confirm the price first — pay on delivery");
        }
        String phone = phoneOverride == null || phoneOverride.isBlank() ? null
                : ReportRecipientService.normalizePhone(phoneOverride);
        orderService.collectDeliveryPayment(order.getOrderCode(), phone);
        return Map.of("orderCode", order.getOrderCode(), "message", "Check your phone and enter your M-Pesa PIN");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private List<WholesaleCatalogDto> catalog(Long distributorId) {
        return inventoryService.getWholesaleCatalog(String.valueOf(distributorId)).stream()
                .filter(c -> c.getWholesalePrice() != null && c.getWholesalePrice().signum() > 0)
                .sorted(Comparator.comparing(c -> c.getItemName() == null ? "" : c.getItemName()))
                .toList();
    }

    /** The shop's usual order: its top products with their typical quantity per order. */
    private List<Map<String, Object>> usual(Long distributorId, Long shopId) {
        CrmShopStats st = statsService.statsByShop(distributorId).get(shopId);
        if (st == null || st.getTopItemsJson() == null) return List.of();
        try {
            List<Map<String, Object>> top = objectMapper.readValue(st.getTopItemsJson(), new TypeReference<>() {});
            return top.stream().map(t -> {
                int units = ((Number) t.getOrDefault("units", 0)).intValue();
                int orders = Math.max(1, ((Number) t.getOrDefault("orders", 1)).intValue());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("itemCode", t.get("itemCode"));
                m.put("quantity", Math.max(1, Math.round((float) units / orders)));
                return m;
            }).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> orderView(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderCode", o.getOrderCode());
        m.put("status", o.getStatus());
        m.put("totalKes", o.getTotalAmount());
        m.put("items", o.getItems() == null ? List.of() : o.getItems().stream()
                .map(i -> Map.of("itemName", i.getItemName(), "quantity", i.getQuantity(),
                        "lineTotal", i.getLineTotal() == null ? BigDecimal.ZERO : i.getLineTotal()))
                .toList());
        m.put("shopNote", o.getShopNote());
        m.put("orderDate", o.getOrderDate());
        return m;
    }

    private void notifyDistributor(ShopOrderLink link, Order order, int extras) {
        try {
            String msg = "TRIBE: New order " + order.getOrderCode() + " from " + name(link.getShopMerchantId())
                    + " (placed by the shop) - KES " + order.getTotalAmount().setScale(0, java.math.RoundingMode.HALF_UP)
                    + (extras > 0 ? " + " + extras + " extra request(s)" : "") + ". Open Orders to confirm.";
            recipients.send(String.valueOf(link.getDistributorId()), ReportRecipientService.ReportType.ALERT,
                    msg.replaceAll("[\\\\\"]", " "));
        } catch (Exception e) {
            log.warn("New-order SMS to distributor failed: {}", e.getMessage());
        }
    }

    private String name(Long merchantId) {
        return merchantService.getMerchantById(merchantId).map(Merchant::getBusinessName).orElse("");
    }
}
