package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.FlowCrypto;
import com.dayworks_ltd.loyalty_engine.smm.meta.SmmProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Facebook Page and Instagram comment and message webhooks from Meta. Stored at once; the worker reads them a few seconds later. */
@Slf4j
@RestController
@RequestMapping("/api/v1/smm")
@RequiredArgsConstructor
public class SmmWebhookController {

    private final SmmProperties props;
    private final SmmIntakeService intake;
    private final SmmMessageTracker messages;
    private final ObjectMapper objectMapper;

    @GetMapping("/webhook")
    @Operation(summary = "Meta checks the webhook URL (hub.challenge)")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && !props.getVerifyToken().isBlank() && props.getVerifyToken().equals(token)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    @PostMapping("/webhook")
    @Operation(summary = "New comments and buyer messages on connected Pages and Instagram accounts (from Meta)")
    public ResponseEntity<String> webhook(@RequestBody String rawBody,
                                          @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!FlowCrypto.validSignature(rawBody, signature, props.getAppSecret())) {
            log.warn("SokoReply webhook rejected: bad or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Bad signature");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
            int stored = intake.ingest(payload);
            if (stored > 0) log.info("SokoReply webhook: {} new comment(s)", stored);
            // Messenger and Instagram messages: did a buyer answer one of our private replies?
            int answered = messages.track(payload);
            if (answered > 0) log.info("SokoReply webhook: {} private reply(ies) answered", answered);
        } catch (Exception e) {
            log.error("SokoReply webhook could not be stored", e);
        }
        return ResponseEntity.ok("OK"); // always 200 so Meta does not keep retrying
    }
}
