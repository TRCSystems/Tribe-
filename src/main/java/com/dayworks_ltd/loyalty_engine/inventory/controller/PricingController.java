package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.PricingService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Prices: unit cost, selling price and wholesale price per item. Costs and margins are owner-only:
 * a MERCHANT login must be unlocked with the SMS code (X-Stock-View-Token), staff never see them.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory/prices")
@RequiredArgsConstructor
@Tag(name = "Prices", description = "Unit cost, selling and wholesale prices (owner only)")
public class PricingController {

    private final PricingService pricingService;
    private final StockVisibility stockVisibility;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "Every item with unit cost, selling price, wholesale price and margins")
    public ResponseEntity<?> list(@AuthenticationPrincipal CustomUserDetails principal,
                                  @RequestParam(required = false) String merchantId) {
        return handle(principal, merchantId, (user, merchant) -> ok("Prices", pricingService.list(merchant)));
    }

    @PutMapping
    @Operation(summary = "Change prices for one or more items (all or nothing)",
            description = "Body: {\"updates\":[{\"id\":12,\"unitCost\":80,\"unitPrice\":120,\"wholesalePrice\":100}]}. "
                    + "Leave a field out to keep it; \"clearWholesalePrice\":true removes the wholesale price.")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> update(@AuthenticationPrincipal CustomUserDetails principal,
                                    @RequestParam(required = false) String merchantId,
                                    @RequestBody Map<String, Object> body) {
        return handle(principal, merchantId, (user, merchant) -> {
            Object raw = body == null ? null : body.get("updates");
            if (!(raw instanceof List<?> list)) return fail(HttpStatus.BAD_REQUEST, "updates must be a list");
            List<PricingService.PriceUpdate> updates = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) return fail(HttpStatus.BAD_REQUEST, "Each update must be an object");
                updates.add(new PricingService.PriceUpdate(
                        toLong(m.get("id")),
                        toMoney(m.get("unitCost")),
                        toMoney(m.get("unitPrice")),
                        toMoney(m.get("wholesalePrice")),
                        Boolean.TRUE.equals(m.get("clearWholesalePrice"))));
            }
            List<Map<String, Object>> rows = pricingService.update(merchant, user.getUserId(), updates);
            return ok(rows.size() == 1 ? "Price updated" : rows.size() + " items updated", rows);
        });
    }

    @GetMapping("/{inventoryId}/history")
    @Operation(summary = "Price changes for one item, newest first")
    public ResponseEntity<?> history(@AuthenticationPrincipal CustomUserDetails principal,
                                     @PathVariable Long inventoryId,
                                     @RequestParam(required = false) String merchantId) {
        return handle(principal, merchantId, (user, merchant) -> ok("Price history", pricingService.history(merchant, inventoryId)));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private interface Action {
        ResponseEntity<?> run(User user, String merchantId);
    }

    private ResponseEntity<?> handle(CustomUserDetails principal, String merchantParam, Action action) {
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
                return fail(HttpStatus.FORBIDDEN, "Only the owner can view or change prices");
            }
            if (merchantId == null || merchantId.isBlank()) {
                return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
            }
            return action.run(user, merchantId);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Prices request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Could not complete the price request");
        }
    }

    private static BigDecimal toMoney(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return new BigDecimal(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid amount: " + o);
        }
    }

    private static Long toLong(Object o) {
        try {
            return o == null ? null : Long.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid item id: " + o);
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
