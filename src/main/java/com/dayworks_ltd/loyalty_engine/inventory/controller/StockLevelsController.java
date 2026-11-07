package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.WholesalePriceConfigRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockCountQueueService;
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
 * Owner-only view of real stock quantities. Quantities are hidden everywhere else at the shop;
 * this report unlocks for a short time after the owner enters a code SMS'd to the registered phone.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory/stock-levels")
@RequiredArgsConstructor
@Tag(name = "Stock Levels API", description = "Owner-verified view of stock quantities")
public class StockLevelsController {

    private final StockVisibility stockVisibility;
    private final StockCountQueueService queueService;
    private final UserRepository userRepository;
    private final WholesalePriceConfigRepository wholesaleRepository;

    @GetMapping("/status")
    @Operation(summary = "Whether stock quantities are unlocked for this browser")
    public ResponseEntity<?> status(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, user -> ok("Stock view status", stockVisibility.status(user)));
    }

    @PostMapping("/request-otp")
    @Operation(summary = "Send an unlock code to the owner's registered phone (MERCHANT only)")
    public ResponseEntity<?> requestOtp(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, user -> ok("Code sent", stockVisibility.requestOtp(user)));
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify the code; returns a token to send as X-Stock-View-Token",
            description = "Body: {\"otp\": \"123456\"}")
    public ResponseEntity<?> verify(@AuthenticationPrincipal CustomUserDetails principal,
                                    @RequestBody Map<String, String> body) {
        return handle(principal, user -> ok("Stock quantities unlocked",
                stockVisibility.verifyOtp(user, body == null ? null : body.get("otp"))));
    }

    @GetMapping
    @Operation(summary = "Current stock quantities (requires owner verification or ADMIN)",
            description = "ADMIN may pass merchantId.")
    public ResponseEntity<?> levels(@AuthenticationPrincipal CustomUserDetails principal,
                                    @RequestParam(required = false) String merchantId) {
        return handle(principal, user -> {
            if (stockVisibility.isBlindToStock(user)) {
                return fail(HttpStatus.LOCKED, StockVisibility.LOCKED_MESSAGE);
            }
            String target = user.getRole() == UserRole.ADMIN && merchantId != null && !merchantId.isBlank()
                    ? merchantId
                    : user.getMerchantId();
            if (target == null) {
                return fail(HttpStatus.BAD_REQUEST, "merchantId is required");
            }

            List<Map<String, Object>> items = new ArrayList<>();
            BigDecimal totalCostValue = BigDecimal.ZERO;
            BigDecimal totalRetailValue = BigDecimal.ZERO;
            BigDecimal totalWholesaleValue = BigDecimal.ZERO;
            int outOfStock = 0, belowReorder = 0, inStockWithoutWholesale = 0;

            // Latest active wholesale price per item code (newest first, as on the Prices page)
            Map<String, BigDecimal> wholesaleByCode = new HashMap<>();
            for (WholesalePriceConfig w : wholesaleRepository.findActiveByMerchant(target)) {
                if (w.getItemCode() != null && w.getWholesalePrice() != null) wholesaleByCode.putIfAbsent(w.getItemCode(), w.getWholesalePrice());
            }

            for (Inventory i : queueService.currentStockByItemCode(target).values()) {
                if (Boolean.FALSE.equals(i.getIsActive())) continue;
                int available = i.getAvailableStock() == null ? 0 : i.getAvailableStock();
                int reorder = i.getReorderLevel() == null ? 0 : i.getReorderLevel();
                BigDecimal cost = i.getUnitCost() == null ? BigDecimal.ZERO : i.getUnitCost();
                BigDecimal price = i.getUnitPrice() == null ? BigDecimal.ZERO : i.getUnitPrice();
                BigDecimal costValue = cost.multiply(BigDecimal.valueOf(available));

                String status = available <= 0 ? "OUT_OF_STOCK" : available <= reorder ? "REORDER" : "OK";
                if (available <= 0) outOfStock++;
                else if (available <= reorder) belowReorder++;
                totalCostValue = totalCostValue.add(costValue);
                totalRetailValue = totalRetailValue.add(price.multiply(BigDecimal.valueOf(available)));
                BigDecimal wholesale = i.getItemCode() == null ? null : wholesaleByCode.get(i.getItemCode());
                if (wholesale != null) {
                    totalWholesaleValue = totalWholesaleValue.add(wholesale.multiply(BigDecimal.valueOf(available)));
                } else if (available > 0) {
                    inStockWithoutWholesale++;
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.getId());
                m.put("itemName", i.getItemName());
                m.put("itemCode", i.getItemCode());
                m.put("availableStock", available);
                m.put("startingStock", i.getStartingStock());
                m.put("addedStock", i.getAddedStock());
                m.put("soldStock", i.getSoldStock());
                m.put("reorderLevel", reorder);
                m.put("unitCost", cost);
                m.put("unitPrice", price);
                m.put("wholesalePrice", wholesale);
                m.put("stockValueAtCost", costValue);
                m.put("status", status);
                m.put("lastUpdated", i.getLastUpdated());
                items.add(m);
            }
            items.sort(Comparator.comparing((Map<String, Object> m) -> statusOrder((String) m.get("status")))
                    .thenComparing(m -> ((String) m.get("itemName")).toLowerCase()));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("itemCount", items.size());
            data.put("outOfStock", outOfStock);
            data.put("belowReorderLevel", belowReorder);
            data.put("totalValueAtCost", totalCostValue);
            data.put("totalValueAtRetail", totalRetailValue);
            data.put("totalValueAtWholesale", totalWholesaleValue);
            data.put("inStockWithoutWholesalePrice", inStockWithoutWholesale);
            data.put("access", stockVisibility.status(user));
            return ok("Stock levels", data);
        });
    }

    private static int statusOrder(String status) {
        return switch (status) {
            case "OUT_OF_STOCK" -> 0;
            case "REORDER" -> 1;
            default -> 2;
        };
    }

    // ───────────────────────────── helpers ─────────────────────────────

    @FunctionalInterface
    private interface Action {
        ResponseEntity<?> run(User user);
    }

    private ResponseEntity<?> handle(CustomUserDetails principal, Action action) {
        try {
            if (principal == null) {
                return fail(HttpStatus.UNAUTHORIZED, "Authentication required");
            }
            User user = userRepository.getUserById(principal.getUserId());
            if (user == null) {
                return fail(HttpStatus.UNAUTHORIZED, "User not found");
            }
            if (user.getRole() == UserRole.SALES_PERSON) {
                return fail(HttpStatus.FORBIDDEN, "Only the shop owner can view stock quantities");
            }
            return action.run(user);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return fail(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("Stock levels request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Stock levels request failed");
        }
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
