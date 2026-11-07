package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockAuditTrailService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Stock audit trail report (owner only — it shows quantities): per-product summary and one
 * product's full movement history. MERCHANT logins need the SMS-code unlock (X-Stock-View-Token).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory/audit-trail")
@RequiredArgsConstructor
@Tag(name = "Stock audit trail", description = "How each item's stock moved: added, sold, dispatched, corrected")
public class StockAuditTrailController {

    private final StockAuditTrailService auditTrailService;
    private final StockVisibility stockVisibility;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "Per-product movements in a period (default: last 30 days)")
    public ResponseEntity<?> summary(@AuthenticationPrincipal CustomUserDetails principal,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) String merchantId) {
        return handle(principal, merchantId, (user, merchant) ->
                ok("Stock audit trail", auditTrailService.summary(merchant, StockAuditTrailService.period(from, to))));
    }

    @GetMapping("/item")
    @Operation(summary = "Every movement of one product in a period, oldest first")
    public ResponseEntity<?> item(@AuthenticationPrincipal CustomUserDetails principal,
                                  @RequestParam String itemCode,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) String merchantId) {
        return handle(principal, merchantId, (user, merchant) ->
                ok("Item audit trail", auditTrailService.itemTrail(merchant, itemCode, StockAuditTrailService.period(from, to))));
    }

    private ResponseEntity<?> handle(CustomUserDetails principal, String merchantParam,
                                     BiFunction<User, String, ResponseEntity<?>> action) {
        try {
            if (principal == null) return fail(HttpStatus.UNAUTHORIZED, "Authentication required");
            User user = userRepository.getUserById(principal.getUserId());
            if (user == null) return fail(HttpStatus.UNAUTHORIZED, "User not found");

            String merchantId;
            if (user.getRole() == UserRole.ADMIN) {
                merchantId = merchantParam != null && !merchantParam.isBlank() ? merchantParam : user.getMerchantId();
            } else if (user.getRole() == UserRole.MERCHANT) {
                if (stockVisibility.isBlindToStock(user)) return fail(HttpStatus.LOCKED, StockVisibility.LOCKED_MESSAGE);
                merchantId = user.getMerchantId();
            } else {
                return fail(HttpStatus.FORBIDDEN, "Only the owner can view the stock audit trail");
            }
            if (merchantId == null || merchantId.isBlank()) {
                return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
            }
            return action.apply(user, merchantId);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Stock audit trail request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Could not load the stock audit trail");
        }
    }

    private static ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "SUCCESS");
        m.put("message", message);
        m.put("data", data);
        return ResponseEntity.ok(m);
    }

    private static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(),
                "message", message == null ? "Request failed" : message));
    }
}
