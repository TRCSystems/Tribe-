package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extra phone numbers that receive stock count SMS (daily summary and/or instant shortage alerts).
 * Changing the list needs the owner's SMS unlock: those messages contain system figures, so an
 * attendant using the owner's login must not be able to add their own number.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory/report-recipients")
@RequiredArgsConstructor
@Tag(name = "Report Recipients API", description = "Who receives stock count SMS")
public class ReportRecipientController {

    private final ReportRecipientService recipientService;
    private final StockVisibility stockVisibility;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "List report recipients (owner, after SMS unlock)")
    public ResponseEntity<?> list(@AuthenticationPrincipal CustomUserDetails principal,
                                  @RequestParam(required = false) String merchantId) {
        return handle(principal, merchantId, (user, target) -> ok("Report recipients", recipientService.list(target)));
    }

    @PostMapping
    @Operation(summary = "Add a recipient",
            description = "Body: {\"name\": \"Jane\", \"phone\": \"0712345678\", \"receiveSummary\": true, \"receiveAlerts\": true}")
    public ResponseEntity<?> add(@AuthenticationPrincipal CustomUserDetails principal,
                                 @RequestParam(required = false) String merchantId,
                                 @RequestBody Map<String, Object> body) {
        return handle(principal, merchantId, (user, target) -> ok("Recipient added", recipientService.add(
                target,
                str(body.get("name")),
                str(body.get("phone")),
                bool(body.get("receiveSummary")),
                bool(body.get("receiveAlerts")),
                user.getUserId())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a recipient or change which reports they get")
    public ResponseEntity<?> update(@AuthenticationPrincipal CustomUserDetails principal,
                                    @PathVariable Long id,
                                    @RequestParam(required = false) String merchantId,
                                    @RequestBody Map<String, Object> body) {
        return handle(principal, merchantId, (user, target) -> ok("Recipient updated", recipientService.update(
                target, id, str(body.get("name")), bool(body.get("receiveSummary")), bool(body.get("receiveAlerts")))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a recipient")
    public ResponseEntity<?> remove(@AuthenticationPrincipal CustomUserDetails principal,
                                    @PathVariable Long id,
                                    @RequestParam(required = false) String merchantId) {
        return handle(principal, merchantId, (user, target) -> {
            recipientService.remove(target, id);
            return ok("Recipient removed", null);
        });
    }

    // ───────────────────────────── helpers ─────────────────────────────

    @FunctionalInterface
    private interface Action {
        ResponseEntity<?> run(User user, String merchantId);
    }

    private ResponseEntity<?> handle(CustomUserDetails principal, String requestedMerchantId, Action action) {
        try {
            if (principal == null) return fail(HttpStatus.UNAUTHORIZED, "Authentication required");
            User user = userRepository.getUserById(principal.getUserId());
            if (user == null) return fail(HttpStatus.UNAUTHORIZED, "User not found");
            if (user.getRole() == UserRole.SALES_PERSON) {
                return fail(HttpStatus.FORBIDDEN, "Only the shop owner can manage report recipients");
            }
            if (stockVisibility.isBlindToStock(user)) {
                return fail(HttpStatus.LOCKED, StockVisibility.LOCKED_MESSAGE);
            }
            String target = user.getRole() == UserRole.ADMIN && requestedMerchantId != null && !requestedMerchantId.isBlank()
                    ? requestedMerchantId
                    : user.getMerchantId();
            if (target == null) return fail(HttpStatus.BAD_REQUEST, "merchantId is required");
            return action.run(user, target);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return fail(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("Report recipient request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Report recipient request failed");
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Boolean bool(Object o) {
        return o == null ? null : Boolean.valueOf(o.toString());
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
        return ResponseEntity.status(status).body(Map.of(
                "status", "FAILURE",
                "statusCode", status.value(),
                "message", message
        ));
    }
}
