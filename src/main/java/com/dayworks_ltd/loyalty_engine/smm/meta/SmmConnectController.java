package com.dayworks_ltd.loyalty_engine.smm.meta;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Connect Facebook & Instagram" in the portal. The merchant is always the logged-in user's; the callback is opened
 * by Facebook in the seller's browser and trusts only the signed state.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/smm")
@RequiredArgsConstructor
public class SmmConnectController {

    private final SmmConnectService connect;
    private final SmmProperties props;

    @GetMapping("/connect/url")
    @Operation(summary = "Facebook login link for connecting the shop's Pages and Instagram")
    public ResponseEntity<?> connectUrl(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        try {
            return ok("Open this link to connect", Map.of("url", connect.loginUrl(user.getMerchantId(), user.getUserId())));
        } catch (IllegalStateException e) {
            return fail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @GetMapping("/connect/callback")
    @Operation(summary = "Facebook sends the seller back here after login (not called by the portal)")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(name = "error_description", required = false) String error) {
        UriComponentsBuilder back = UriComponentsBuilder.fromUriString(props.getPortalUrl()).path("/sokoreply");
        if (code == null) {
            back.queryParam("error", error != null ? error : "Facebook did not give access");
        } else {
            try {
                SmmConnectService.Connected done = connect.complete(code, state);
                back.queryParam("connected", done.names().size());
                if (!done.skipped().isEmpty()) back.queryParam("skipped", String.join("; ", done.skipped()));
            } catch (IllegalArgumentException e) {
                back.queryParam("error", e.getMessage());
            } catch (Exception e) {
                log.error("Connecting Pages failed: {}", MetaGraphClient.redact(e.getMessage()));
                back.queryParam("error", "Connecting failed. Please try again.");
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(back.encode().build().toUriString()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/accounts")
    @Operation(summary = "The shop's connected Pages and Instagram accounts")
    public ResponseEntity<?> accounts(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        List<Map<String, Object>> list = connect.list(user.getMerchantId()).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("platform", a.getPlatform());
            m.put("pageId", a.getPageId());
            m.put("name", a.getPageName());
            m.put("status", a.getStatus());
            m.put("lastWebhookAt", a.getLastWebhookAt());
            m.put("connectedAt", a.getCreatedAt());
            return m;
        }).toList();
        return ok("Connected accounts", list);
    }

    @PostMapping("/accounts/{id}/disconnect")
    @Operation(summary = "Disconnect a Page or Instagram account (stops comments; the token is deleted)")
    public ResponseEntity<?> disconnect(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        try {
            connect.disconnect(user.getMerchantId(), id);
            return ok("Disconnected", null);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    static ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("statusCode", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(), "message", message));
    }
}
