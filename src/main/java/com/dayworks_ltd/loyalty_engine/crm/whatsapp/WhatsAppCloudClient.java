package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/** Thin wrapper over the Meta WhatsApp Cloud API (graph.facebook.com). */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppCloudClient {

    private static final String GRAPH = "https://graph.facebook.com/";

    private final WhatsAppProperties props;
    private final RestTemplate restTemplate;

    /** What Meta answered: ok + the wamid, or the error text. */
    public record Sent(boolean ok, String messageId, String error) {}

    public record Button(String id, String title) {}

    public record Row(String id, String title, String description) {}

    // ───────────────────────────── messages ─────────────────────────────

    /** Free-form text — only allowed within 24h of the shop's last message. */
    public Sent sendText(String to, String text) {
        return send(to, "text", Map.of("body", limit(text, 4096), "preview_url", false));
    }

    /** A photo or file a customer sent: its bytes and type. */
    public record Media(byte[] bytes, String mimeType) {}

    /** Largest photo we read (Meta allows 5 MB images; Claude reads up to 5 MB per image). */
    private static final long MAX_MEDIA_BYTES = 5L * 1024 * 1024;

    /**
     * Downloads media a customer sent. WhatsApp only gives us an id: look up its short-lived URL, then
     * fetch it with the same token. Returns null when it can't be fetched or is too large.
     */
    @SuppressWarnings("unchecked")
    public Media downloadMedia(String mediaId) {
        if (mediaId == null || mediaId.isBlank() || props.getAccessToken().isBlank()) return null;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(props.getAccessToken());
        try {
            ResponseEntity<Map> info = restTemplate.exchange(GRAPH + props.getGraphVersion() + "/" + mediaId,
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> meta = info.getBody() == null ? Map.of() : info.getBody();
            Object url = meta.get("url");
            Object size = meta.get("file_size");
            if (url == null) return null;
            if (size instanceof Number n && n.longValue() > MAX_MEDIA_BYTES) {
                log.warn("WhatsApp media {} is too large ({} bytes)", mediaId, n);
                return null;
            }
            ResponseEntity<byte[]> file = restTemplate.exchange(java.net.URI.create(url.toString()),
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] bytes = file.getBody();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_MEDIA_BYTES) return null;
            return new Media(bytes, String.valueOf(meta.getOrDefault("mime_type", "image/jpeg")));
        } catch (Exception e) {
            log.warn("WhatsApp media {} could not be downloaded: {}", mediaId, e.getMessage());
            return null;
        }
    }

    /** Marks the shopper's message as read and shows "typing…" while we work on the reply (max ~25 s). */
    public void markReadAndTyping(String messageId) {
        if (!props.canSend() || messageId == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("status", "read");
        body.put("message_id", messageId);
        body.put("typing_indicator", Map.of("type", "text"));
        Map<String, Object> res = post(props.getPhoneNumberId() + "/messages", body);
        if (res.containsKey("error")) log.debug("Typing indicator not shown: {}", res.get("error"));
    }

    /** A message with one button that opens a web page (e.g. the web order page). */
    public Sent sendLinkButton(String to, String header, String body, String buttonText, String url) {
        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "cta_url");
        interactive.put("header", Map.of("type", "text", "text", limit(header, 60)));
        interactive.put("body", Map.of("text", limit(body, 1024)));
        interactive.put("footer", Map.of("text", "Pay on delivery or M-Pesa"));
        interactive.put("action", Map.of("name", "cta_url",
                "parameters", Map.of("display_text", limit(buttonText, 20), "url", url)));
        return send(to, "interactive", interactive);
    }

    /** Up to 3 reply buttons (title max 20 chars). */
    public Sent sendButtons(String to, String body, List<Button> buttons) {
        List<Map<String, Object>> list = buttons.stream().limit(3)
                .map(b -> Map.<String, Object>of("type", "reply",
                        "reply", Map.of("id", b.id(), "title", limit(b.title(), 20))))
                .toList();
        return send(to, "interactive", Map.of(
                "type", "button",
                "body", Map.of("text", limit(body, 1024)),
                "action", Map.of("buttons", list)));
    }

    /** A pick-one list (up to 10 rows; title max 24, description max 72). */
    public Sent sendList(String to, String body, String buttonLabel, List<Row> rows) {
        List<Map<String, Object>> list = rows.stream().limit(10).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("title", limit(r.title(), 24));
            if (r.description() != null && !r.description().isBlank()) m.put("description", limit(r.description(), 72));
            return m;
        }).toList();
        return send(to, "interactive", Map.of(
                "type", "list",
                "body", Map.of("text", limit(body, 1024)),
                "action", Map.of("button", limit(buttonLabel, 20),
                        "sections", List.of(Map.of("title", "Choose", "rows", list)))));
    }

    /** Opens the order form (free-form, so only within the 24h window). */
    public Sent sendOrderFlow(String to, String header, String body, String flowToken) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("flow_message_version", "3");
        parameters.put("flow_token", flowToken);
        parameters.put("flow_id", props.getFlowId());
        parameters.put("flow_cta", "Order now");
        parameters.put("flow_action", "data_exchange");
        if ("draft".equalsIgnoreCase(props.getFlowMode())) parameters.put("mode", "draft");
        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "flow");
        interactive.put("header", Map.of("type", "text", "text", limit(header, 60)));
        interactive.put("body", Map.of("text", limit(body, 1024)));
        interactive.put("footer", Map.of("text", "Pay on delivery or M-Pesa"));
        interactive.put("action", Map.of("name", "flow", "parameters", parameters));
        return send(to, "interactive", interactive);
    }

    /**
     * An approved template. bodyParams fill {{1}}, {{2}}…; flowToken (optional) goes to the
     * template's "Order now" flow button.
     */
    public Sent sendTemplate(String to, String templateName, List<String> bodyParams, String flowToken) {
        List<Map<String, Object>> components = new ArrayList<>();
        if (!bodyParams.isEmpty()) {
            components.add(Map.of("type", "body", "parameters", bodyParams.stream()
                    .map(p -> Map.of("type", "text", "text", p == null || p.isBlank() ? "-" : limit(p, 300)))
                    .toList()));
        }
        if (flowToken != null) {
            components.add(Map.of("type", "button", "sub_type", "flow", "index", "0",
                    "parameters", List.of(Map.of("type", "action", "action", Map.of("flow_token", flowToken)))));
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", props.getTemplateLanguage()));
        if (!components.isEmpty()) template.put("components", components);
        return send(to, "template", template);
    }

    // ───────────────────────────── setup (admin) ─────────────────────────────

    /** Registers the flow-encryption public key on the Tribe number. */
    public Map<String, Object> registerPublicKey(String publicKeyPem) {
        return post(props.getPhoneNumberId() + "/whatsapp_business_encryption",
                Map.of("business_public_key", publicKeyPem));
    }

    /** Submits one message template for Meta approval. */
    public Map<String, Object> createTemplate(Map<String, Object> definition) {
        return post(props.getWabaId() + "/message_templates", definition);
    }

    // ───────────────────────────── internals ─────────────────────────────

    private Sent send(String to, String type, Map<String, Object> content) {
        if (!props.canSend()) {
            log.warn("WhatsApp {} to {} not sent: WhatsApp is not configured", type, to);
            return new Sent(false, null, "WhatsApp is not configured");
        }
        String waTo = toWaNumber(to);
        if (waTo == null) return new Sent(false, null, "Invalid phone number");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", waTo);
        body.put("type", type);
        body.put(type, content);
        Map<String, Object> res = post(props.getPhoneNumberId() + "/messages", body);
        if (res.containsKey("error")) {
            log.warn("WhatsApp {} to {} refused: {}", type, waTo, res.get("error"));
            return new Sent(false, null, String.valueOf(res.get("error")));
        }
        Object messages = res.get("messages");
        String id = null;
        if (messages instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            id = String.valueOf(m.get("id"));
        }
        log.info("WhatsApp {} to {} accepted: {}", type, waTo, id);
        return new Sent(id != null, id, id == null ? "No message id returned" : null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(props.getAccessToken());
        try {
            ResponseEntity<Map> res = restTemplate.postForEntity(GRAPH + props.getGraphVersion() + "/" + path,
                    new HttpEntity<>(body, headers), Map.class);
            return res.getBody() == null ? Map.of() : res.getBody();
        } catch (HttpStatusCodeException e) {
            String detail = e.getResponseBodyAsString();
            log.warn("WhatsApp API {} failed ({}): {}", path, e.getStatusCode().value(), detail);
            return Map.of("error", detail.isBlank() ? e.getStatusCode().toString() : detail);
        } catch (Exception e) {
            log.warn("WhatsApp API {} failed: {}", path, e.getMessage());
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    /** 0712345678 / +254712345678 / 712345678 → 254712345678. */
    public static String toWaNumber(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.startsWith("0") && d.length() == 10) d = "254" + d.substring(1);
        else if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) d = "254" + d;
        return d.matches("254[17]\\d{8}") ? d : null;
    }

    private static String limit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
