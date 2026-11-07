package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderLinkService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.*;

/**
 * WhatsApp (Meta Cloud API) endpoints:
 *  - /webhook  — Meta's verification handshake and incoming messages / receipts (public, signed by Meta)
 *  - /flow     — the order form's data endpoint (public, encrypted by Meta)
 *  - /admin/*  — one-off setup: status, flow JSON, key registration, template submission (ADMIN only)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/whatsapp")
@RequiredArgsConstructor
@Tag(name = "WhatsApp", description = "Tribe WhatsApp number: webhook, order form endpoint and setup")
public class WhatsAppController {

    private final WhatsAppProperties props;
    private final WhatsAppInboundService inboundService;
    private final OrderFlowService orderFlowService;
    private final WhatsAppCloudClient client;
    private final ShopOrderLinkService linkService;
    private final CrmContactService contactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile PrivateKey flowKey;

    private final java.util.concurrent.ExecutorService webhookWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whatsapp-webhook");
        t.setDaemon(true);
        return t;
    });

    // ───────────────────────────── webhook ─────────────────────────────

    @GetMapping("/webhook")
    @Operation(summary = "Meta webhook verification")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && !props.getVerifyToken().isBlank() && props.getVerifyToken().equals(token)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    @PostMapping("/webhook")
    @Operation(summary = "Incoming WhatsApp messages and delivery receipts (from Meta)")
    public ResponseEntity<String> webhook(@RequestBody String rawBody,
                                          @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!FlowCrypto.validSignature(rawBody, signature, props.getAppSecret())) {
            log.warn("WhatsApp webhook rejected: bad or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Bad signature");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
            // Answer Meta at once; replies (which may wait on Claude or M-Pesa) are worked on in order, one at a time
            webhookWorker.execute(() -> {
                try {
                    inboundService.process(payload);
                } catch (Exception e) {
                    log.error("WhatsApp webhook could not be processed", e);
                }
            });
        } catch (Exception e) {
            log.error("WhatsApp webhook could not be read", e);
        }
        return ResponseEntity.ok("OK"); // always 200 so Meta doesn't keep retrying
    }

    // ───────────────────────────── order form (Flow) endpoint ─────────────────────────────

    @PostMapping("/flow")
    @Operation(summary = "WhatsApp order form data endpoint (encrypted, from Meta)")
    public ResponseEntity<String> flow(@RequestBody String rawBody,
                                       @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!FlowCrypto.validSignature(rawBody, signature, props.getAppSecret())) {
            log.warn("WhatsApp flow request rejected: bad or missing signature");
            return ResponseEntity.status(432).body("Bad signature");
        }
        FlowCrypto.Decrypted request;
        try {
            Map<String, String> body = objectMapper.readValue(rawBody, new TypeReference<>() {});
            request = FlowCrypto.decrypt(body.get("encrypted_flow_data"), body.get("encrypted_aes_key"),
                    body.get("initial_vector"), flowKey());
        } catch (Exception e) {
            log.warn("WhatsApp flow request could not be decrypted: {}", e.getMessage());
            return ResponseEntity.status(421).body("Decryption failed"); // tells WhatsApp to refresh our public key
        }
        Map<String, Object> response;
        try {
            response = orderFlowService.handle(objectMapper.readValue(request.json(), new TypeReference<>() {}));
        } catch (Exception e) {
            log.error("WhatsApp flow request failed", e);
            response = Map.of("screen", "CLOSED", "data", Map.of("message",
                    "Something went wrong on our side. Send ORDER on chat to try again."));
        }
        try {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                    .body(FlowCrypto.encryptResponse(objectMapper.writeValueAsString(response), request));
        } catch (Exception e) {
            log.error("WhatsApp flow response could not be encrypted", e);
            return ResponseEntity.internalServerError().body("Error");
        }
    }

    // ───────────────────────────── setup (ADMIN; secured in WebSecurityConfig) ─────────────────────────────

    @GetMapping("/admin/status")
    @Operation(summary = "What is configured and what is missing")
    public ResponseEntity<?> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("canSend", props.canSend());
        s.put("missingSettings", props.missingSettings());
        try {
            s.put("flowPublicKey", FlowCrypto.publicKeyPem(flowKey()));
        } catch (Exception e) {
            s.put("flowPublicKey", null);
            s.put("flowKeyProblem", e.getMessage());
        }
        s.put("templates", WhatsAppTemplates.all().stream().map(t -> Map.of(
                "name", t.name(), "category", t.marketing() ? "MARKETING" : "UTILITY", "text", t.metaText(),
                "orderButton", t.orderButton())).toList());
        return ResponseEntity.ok(s);
    }

    @GetMapping(value = "/admin/flow-json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The order form JSON to paste into WhatsApp Manager → Flows")
    public ResponseEntity<String> flowJson() throws Exception {
        return ResponseEntity.ok(new String(new ClassPathResource("whatsapp/order-flow.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
    }

    @PostMapping("/admin/register-key")
    @Operation(summary = "Register the order form encryption public key on the Tribe number")
    public ResponseEntity<?> registerKey() {
        return ResponseEntity.ok(client.registerPublicKey(FlowCrypto.publicKeyPem(flowKey())));
    }

    @PostMapping("/admin/templates")
    @Operation(summary = "Submit all CRM templates to Meta for approval (existing ones are reported, not changed)")
    public ResponseEntity<?> submitTemplates() {
        if (props.getFlowId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Set whatsapp.flow-id first (the order form must be published)"));
        }
        Map<String, Object> results = new LinkedHashMap<>();
        for (WhatsAppTemplates.WaTemplate t : WhatsAppTemplates.all()) {
            results.put(t.name(), client.createTemplate(
                    WhatsAppTemplates.metaDefinition(t, props.getTemplateLanguage(), props.getFlowId())));
        }
        return ResponseEntity.ok(results);
    }

    @PostMapping("/admin/test-order-form")
    @Operation(summary = "Send the order form to a phone (that phone must have messaged the Tribe number in the last 24h)",
            description = "Body: {\"phone\":\"0712345678\",\"distributorId\":23,\"shopId\":123}")
    public ResponseEntity<?> testOrderForm(@RequestBody Map<String, Object> body) {
        String phone = String.valueOf(body.get("phone"));
        Long distributorId = Long.valueOf(String.valueOf(body.get("distributorId")));
        Long shopId = Long.valueOf(String.valueOf(body.get("shopId")));
        contactService.getOrCreate(distributorId, shopId);
        ShopOrderLinkService.NewLink link = linkService.create(distributorId, shopId, null);
        WhatsAppCloudClient.Sent sent = client.sendOrderFlow(phone, "Test order form",
                "Test: tap Order now to see the order form.", link.token());
        return ResponseEntity.ok(Map.of("sent", sent.ok(), "messageId", String.valueOf(sent.messageId()),
                "error", String.valueOf(sent.error())));
    }

    private PrivateKey flowKey() {
        PrivateKey key = flowKey;
        if (key == null) {
            if (props.getFlowPrivateKeyPath().isBlank()) throw new IllegalStateException("whatsapp.flow-private-key-path is not set");
            try {
                key = FlowCrypto.readPrivateKey(Files.readString(Path.of(props.getFlowPrivateKeyPath())));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read " + props.getFlowPrivateKeyPath() + ": " + e.getMessage());
            }
            flowKey = key;
        }
        return key;
    }
}
