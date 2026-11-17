package com.dayworks_ltd.loyalty_engine.smm.billing;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmPlan;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSubscription;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** SokoReply's billing page: the three plans, the seller's current subscription, and M-Pesa STK push checkout. */
@Slf4j
@RestController
@RequestMapping("/api/v1/smm/billing")
@RequiredArgsConstructor
public class SmmBillingController {

    private final SmmBillingService billing;

    @GetMapping("/plans")
    @Operation(summary = "The three SokoReply plans and what each includes")
    public ResponseEntity<?> plans() {
        return ok("Plans", billing.plans());
    }

    @GetMapping("/status")
    @Operation(summary = "This shop's current subscription, if any")
    public ResponseEntity<?> status(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        Optional<SmmSubscription> sub = billing.subscriptionFor(user.getMerchantId());
        return ok("Subscription", sub.map(this::view).orElse(null));
    }

    @GetMapping("/history")
    @Operation(summary = "Every payment this shop has made, most recent first")
    public ResponseEntity<?> history(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        return ok("Payment history", billing.paymentHistory(user.getMerchantId()));
    }

    public record SubscribeRequest(String plan, String platform, String phoneNumber) {}

    @PostMapping("/subscribe")
    @Operation(summary = "Pick a plan and pay for it via M-Pesa STK push")
    public ResponseEntity<?> subscribe(@AuthenticationPrincipal CustomUserDetails user, @RequestBody SubscribeRequest body) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        try {
            SmmPlan plan = SmmPlan.valueOf(body.plan().toUpperCase());
            SocialPlatform platform = body.platform() == null ? null : SocialPlatform.valueOf(body.platform().toUpperCase());
            SmmSubscription sub = billing.subscribe(user.getMerchantId(), plan, body.phoneNumber(), platform);
            return ok("Check your phone to complete payment", view(sub));
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.warn("SokoReply billing: subscribe failed for merchant {}: {}", user.getMerchantId(), e.getMessage());
            return fail(HttpStatus.BAD_GATEWAY, "Could not start the M-Pesa payment. Try again.");
        }
    }

    @PostMapping("/confirm")
    @Operation(summary = "Check whether the pending M-Pesa payment has gone through")
    public ResponseEntity<?> confirm(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        try {
            SmmSubscription sub = billing.confirmPayment(user.getMerchantId());
            return ok(sub.isActive() ? "Payment confirmed" : "Payment not confirmed yet", view(sub));
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Map<String, Object> view(SmmSubscription sub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", sub.getPlan());
        m.put("status", sub.getStatus());
        m.put("starterPlatform", sub.getStarterPlatform());
        m.put("currentPeriodEnd", sub.getCurrentPeriodEnd());
        m.put("active", sub.isActive());
        return m;
    }

    private static ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("statusCode", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(), "message", message));
    }
}
