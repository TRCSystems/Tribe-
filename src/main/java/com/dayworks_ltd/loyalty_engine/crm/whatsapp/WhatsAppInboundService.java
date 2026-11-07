package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.crm.channels.SmsChannelSender;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmMessage;
import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmMessageRepository;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderLinkService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderService;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Handles what arrives on the Tribe WhatsApp number (Meta webhook):
 *  - any chat from a known shop → the order form (or a pick-your-distributor list)
 *  - STOP / START → marketing opt-out / opt-in
 *  - "Pay with M-Pesa" tap → M-Pesa prompt for that order
 *  - finished order form → offer to pay now
 *  - delivery receipts → message status; undeliverable WhatsApp messages are re-sent by SMS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppInboundService {

    private static final Set<String> STOP_WORDS = Set.of("stop", "stop all", "unsubscribe", "acha", "cancel offers");
    private static final Set<String> START_WORDS = Set.of("start", "subscribe", "anza");
    /** WhatsApp errors meaning "this message can't reach the number on WhatsApp" — worth an SMS instead. */
    private static final Set<Integer> SMS_FALLBACK_ERRORS = Set.of(131026, 131047, 131049, 131050, 130472);

    private final WhatsAppInboundRepository inboundRepository;
    private final WhatsAppCloudClient client;
    private final CrmContactService contactService;
    private final ShopOrderLinkService linkService;
    private final ShopOrderService shopOrderService;
    private final MerchantService merchantService;
    private final OrderRepository orderRepository;
    private final CrmMessageRepository messageRepository;
    private final SmsChannelSender smsSender;
    private final com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShopAssistant shopAssistant;
    private final com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.WhatsAppReportApproval reportApproval;
    private final com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.WhatsAppStockReceipt stockReceipt;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public void process(Map<String, Object> payload) {
        for (Map<String, Object> entry : list(payload.get("entry"))) {
            for (Map<String, Object> change : list(entry.get("changes"))) {
                if (!(change.get("value") instanceof Map<?, ?> v)) continue;
                Map<String, Object> value = (Map<String, Object>) v;
                Map<String, String> names = new HashMap<>();
                for (Map<String, Object> c : list(value.get("contacts"))) {
                    Object profile = c.get("profile");
                    if (profile instanceof Map<?, ?> p) names.put(String.valueOf(c.get("wa_id")), String.valueOf(p.get("name")));
                }
                for (Map<String, Object> message : list(value.get("messages"))) {
                    try {
                        handleMessage(message, names.get(String.valueOf(message.get("from"))));
                    } catch (Exception e) {
                        log.error("WhatsApp message {} could not be handled", message.get("id"), e);
                    }
                }
                for (Map<String, Object> status : list(value.get("statuses"))) {
                    try {
                        handleStatus(status);
                    } catch (Exception e) {
                        log.error("WhatsApp status {} could not be handled", status.get("id"), e);
                    }
                }
            }
        }
    }

    // ───────────────────────────── incoming messages ─────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleMessage(Map<String, Object> message, String profileName) {
        String id = String.valueOf(message.get("id"));
        String from = String.valueOf(message.get("from"));
        String type = String.valueOf(message.get("type"));

        String content = switch (type) {
            case "text" -> String.valueOf(((Map<String, Object>) message.get("text")).get("body"));
            case "button" -> String.valueOf(((Map<String, Object>) message.get("button")).get("payload"));
            case "interactive" -> interactiveContent((Map<String, Object>) message.get("interactive"));
            default -> "";
        };
        if (!remember(id, from, profileName, type, content)) return; // a retry of something already handled

        // The approver answering a held stock count SMS (Approve / Don't send, or "approved")
        if (reportApproval.handle(from, type, content)) return;
        if (stockReceipt.handleApproval(from, type, content)) return;

        // Consumer shopping (e.g. ASANO Fresh Mart): numbers that aren't a distributor's shop, or every chat in demo mode
        boolean knownShop = !contactService.findByNumber(from).isEmpty();
        if (shopAssistant.handles(knownShop)) {
            shopAssistant.handle(from, profileName, id, type, content, message);
            return;
        }

        List<CrmContact> contacts = contactService.recordWhatsappInbound(from);

        if ("interactive".equals(type)) {
            Map<String, Object> interactive = (Map<String, Object>) message.get("interactive");
            if ("nfm_reply".equals(interactive.get("type"))) {
                orderFormFinished(from, content);
                return;
            }
        }
        String text = content == null ? "" : content.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.startsWith("pay:")) {
            pay(from, text.substring(4));
        } else if (lower.startsWith("dist:")) {
            String[] ids = text.substring(5).split(":");
            sendOrderForm(from, contacts, parseLong(ids[0]), ids.length > 1 ? parseLong(ids[1]) : -1L, null);
        } else if (STOP_WORDS.contains(lower)) {
            contactService.optOutByPhone(from);
            client.sendText(from, "Sawa, no more offers from us. You will still get updates about your orders. "
                    + "Send START anytime to get offers again.");
        } else if (START_WORDS.contains(lower)) {
            contacts.forEach(c -> contactService.update(c.getDistributorId(), c.getShopMerchantId(), null, null, null, false));
            client.sendText(from, "Karibu back! You will get offers again. Send ORDER anytime to order.");
        } else {
            String note = "text".equals(type) || "button".equals(type) || "interactive".equals(type)
                    ? null : "I can only read text messages here.";
            offerOrderForm(from, contacts, note);
        }
    }

    /** Any chat → the order form for that shop; a list first when the number orders from several distributors. */
    private void offerOrderForm(String from, List<CrmContact> contacts, String note) {
        if (contacts.isEmpty()) {
            client.sendText(from, "Hi! This number is not linked to a shop on Tribe yet. Ask your distributor's rep to add "
                    + local(from) + " to your shop, then send ORDER again.");
            return;
        }
        if (contacts.size() == 1) {
            sendOrderForm(from, contacts, contacts.get(0).getDistributorId(), contacts.get(0).getShopMerchantId(), note);
            return;
        }
        // One number, several shops or distributors: let them pick (shop name, ordering from which distributor)
        List<WhatsAppCloudClient.Row> rows = contacts.stream()
                .map(c -> new WhatsAppCloudClient.Row("DIST:" + c.getDistributorId() + ":" + c.getShopMerchantId(),
                        name(c.getShopMerchantId()), "Order from " + name(c.getDistributorId())))
                .toList();
        client.sendList(from, (note == null ? "" : note + " ") + "Which shop are you ordering for?", "Choose", rows);
    }

    private void sendOrderForm(String from, List<CrmContact> contacts, Long distributorId, Long shopId, String note) {
        CrmContact contact = contacts.stream()
                .filter(c -> c.getDistributorId().equals(distributorId) && c.getShopMerchantId().equals(shopId))
                .findFirst().orElse(null);
        if (contact == null) {
            client.sendText(from, "Sorry, that shop is not linked to this number.");
            return;
        }
        if (contact.getWhatsappNumber() == null && !Objects.equals(contact.getPhone(), local(from))) {
            contactService.update(distributorId, contact.getShopMerchantId(), null, local(from), null, null);
        }
        ShopOrderLinkService.NewLink link = linkService.create(distributorId, contact.getShopMerchantId(), null);
        String shop = name(contact.getShopMerchantId());
        String body = (note == null ? "" : note + " ") + "Hi " + shop + "! Tap Order now - your usual is filled in. "
                + "Pay on delivery or by M-Pesa.";
        String header = "Order from " + name(distributorId);
        WhatsAppCloudClient.Sent sent = client.sendOrderFlow(from, header, body, link.token());
        if (!sent.ok()) {
            // The in-WhatsApp form can be refused (e.g. Meta's Flow integrity check right after verification):
            // send the same one-time order link as a button that opens the web order page instead
            log.warn("Order form to {} not sent ({}); sending the web order link instead", from, sent.error());
            WhatsAppCloudClient.Sent fallback = client.sendLinkButton(from, header, body, "Order now", link.url());
            if (!fallback.ok()) {
                client.sendText(from, body + " Order here: " + link.url());
            }
        }
    }

    /** The shop tapped Done on the order form — offer to pay now. */
    private void orderFormFinished(String from, String responseJson) {
        Map<String, Object> response;
        try {
            response = objectMapper.readValue(responseJson, new TypeReference<>() {});
        } catch (Exception e) {
            return;
        }
        Object token = response.get("flow_token");
        if (token == null || response.get("order_code") == null) return;
        ShopOrderLink link = linkService.find(token.toString()).orElse(null);
        if (link == null || link.getOrderId() == null) return;
        Order order = orderRepository.findById(link.getOrderId()).orElse(null);
        if (order == null || order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0
                || order.getStatus() != OrderStatus.PENDING) {
            return;
        }
        client.sendButtons(from, "Asante " + name(link.getShopMerchantId()) + "! Order " + order.getOrderCode()
                        + " is placed - KES " + money(order.getTotalAmount()) + ", pay on delivery. Want to pay now with M-Pesa?",
                List.of(new WhatsAppCloudClient.Button("PAY:" + token, "Pay with M-Pesa")));
    }

    private void pay(String from, String token) {
        try {
            Map<String, Object> result = shopOrderService.pay(token.trim(), local(from));
            client.sendText(from, "Check your phone and enter your M-Pesa PIN to pay for order " + result.get("orderCode") + ".");
        } catch (Exception e) {
            String reason = e instanceof IllegalArgumentException || e instanceof IllegalStateException
                    || e instanceof NoSuchElementException ? e.getMessage() : "the payment service did not respond";
            log.warn("WhatsApp M-Pesa request from {} failed: {}", from, e.getMessage());
            client.sendText(from, "Sorry, we could not send the M-Pesa prompt (" + reason + "). You can pay on delivery.");
        }
    }

    // ───────────────────────────── delivery receipts ─────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleStatus(Map<String, Object> status) {
        String id = String.valueOf(status.get("id"));
        String state = String.valueOf(status.get("status"));
        if ("failed".equals(state)) {
            // Meta explains delivery failures only here (e.g. 131030 number not allowed, 131047 24h window closed)
            log.warn("WhatsApp message {} to {} failed: {}", id, status.get("recipient_id"), status.get("errors"));
        }
        CrmMessage m = messageRepository.findFirstByProviderMessageId(id).orElse(null);
        if (m == null) return;
        if (!"failed".equals(m.getDeliveryStatus())) m.setDeliveryStatus(state);

        if ("failed".equals(state) && m.getChannel() == CrmEnums.Channel.WHATSAPP) {
            Integer code = null;
            List<Map<String, Object>> errors = list(status.get("errors"));
            if (!errors.isEmpty() && errors.get(0).get("code") instanceof Number n) code = n.intValue();
            if (code != null && SMS_FALLBACK_ERRORS.contains(code)) {
                if (code == 131026) contactService.fallBackToSms(m.getToNumber());
                CrmContact contact = contactService.getOrCreate(m.getDistributorId(), m.getShopMerchantId());
                boolean ok = contact.getPhone() != null && smsSender.send(contact.getPhone(), m.getBody());
                m.setChannel(CrmEnums.Channel.SMS);
                m.setToNumber(contact.getPhone());
                m.setStatus(ok ? CrmEnums.MessageStatus.SENT : CrmEnums.MessageStatus.FAILED);
                m.setSkipReason("WhatsApp failed (" + code + "), sent by SMS instead");
            } else {
                m.setStatus(CrmEnums.MessageStatus.FAILED);
                m.setSkipReason("WhatsApp failed" + (code == null ? "" : " (" + code + ")"));
            }
        }
        messageRepository.save(m);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Stores the message; false when it was already handled (Meta retries webhooks). */
    private boolean remember(String id, String from, String profileName, String type, String content) {
        if (inboundRepository.existsByWaMessageId(id)) return false;
        try {
            inboundRepository.save(WhatsAppInbound.builder()
                    .waMessageId(id)
                    .fromNumber(from)
                    .profileName(profileName == null ? null : truncate(profileName, 120))
                    .type(type)
                    .content(content == null ? null : truncate(content, 1000))
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static String interactiveContent(Map<String, Object> interactive) {
        if (interactive == null) return "";
        return switch (String.valueOf(interactive.get("type"))) {
            case "button_reply" -> String.valueOf(((Map<String, Object>) interactive.get("button_reply")).get("id"));
            case "list_reply" -> String.valueOf(((Map<String, Object>) interactive.get("list_reply")).get("id"));
            case "nfm_reply" -> String.valueOf(((Map<String, Object>) interactive.get("nfm_reply")).get("response_json"));
            default -> "";
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(Map.class::isInstance).map(x -> (Map<String, Object>) x).toList();
    }

    private String name(Long merchantId) {
        return merchantService.getMerchantById(merchantId).map(Merchant::getBusinessName).orElse("your distributor");
    }

    /** 254712345678 → 0712345678 */
    private static String local(String waNumber) {
        String d = waNumber == null ? "" : waNumber.replaceAll("\\D", "");
        return d.startsWith("254") && d.length() == 12 ? "0" + d.substring(3) : d;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String money(BigDecimal v) {
        return java.text.NumberFormat.getIntegerInstance(Locale.US).format(v.setScale(0, RoundingMode.HALF_UP));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
