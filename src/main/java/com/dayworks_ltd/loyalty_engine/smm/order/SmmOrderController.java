package com.dayworks_ltd.loyalty_engine.smm.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * The public SokoReply order page — no login; access is the link shared by every comment reply on that Reel. Never
 * answers 401 (a buyer's browser has no session to be logged out of).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/smm/order")
@RequiredArgsConstructor
@Tag(name = "SokoReply order page (public link)", description = "Order from a Facebook/Instagram comment")
public class SmmOrderController {

    private final SmmOrderService orderService;

    @GetMapping("/{token}")
    @Operation(summary = "Catalogue, delivery zones and payment info for this Reel's order page")
    public ResponseEntity<?> view(@PathVariable String token) {
        return handle(() -> orderService.view(token));
    }

    @PostMapping("/{token}/orders")
    @Operation(summary = "Place the order",
            description = "Body: {\"items\":[{\"itemCode\":\"23-ABC\",\"quantity\":1}], \"zoneId\":5, \"phone\":\"0712345678\", "
                    + "\"customerName\":\"Mary\", \"commentId\":\"optional, the comment that brought the buyer here\"}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> place(@PathVariable String token, @RequestBody Map<String, Object> body) {
        return handle(() -> {
            List<SmmOrderService.ItemLine> items = new ArrayList<>();
            for (Map<String, Object> i : (List<Map<String, Object>>) body.getOrDefault("items", List.of())) {
                items.add(new SmmOrderService.ItemLine(String.valueOf(i.get("itemCode")), toInt(i.get("quantity"), 1)));
            }
            Long zoneId = body.get("zoneId") == null ? null : Long.valueOf(String.valueOf(toInt(body.get("zoneId"), 0)));
            String phone = str(body.get("phone"));
            String customerName = str(body.get("customerName"));
            String commentId = str(body.get("commentId"));
            return orderService.placeOrder(token, items, zoneId, phone, customerName, commentId);
        });
    }

    @PostMapping("/{token}/orders/{orderCode}/paid")
    @Operation(summary = "The buyer reports having paid the seller's till", description = "Body (optional): {\"mpesaCode\": \"QK7AB12XYZ\"}")
    public ResponseEntity<?> paid(@PathVariable String token, @PathVariable String orderCode, @RequestBody(required = false) Map<String, String> body) {
        return handle(() -> {
            orderService.reportPaid(orderCode, body == null ? null : body.get("mpesaCode"));
            return Map.of("orderCode", orderCode, "reported", true);
        });
    }

    private ResponseEntity<?> handle(Supplier<Object> action) {
        try {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "SUCCESS");
            ok.put("data", action.get());
            return ResponseEntity.ok(ok);
        } catch (NoSuchElementException e) {
            return fail(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("SokoReply order request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again or message the seller directly.");
        }
    }

    private static int toInt(Object o, int fallback) {
        if (o == null) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(o.toString()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(),
                "message", message == null ? "Request failed" : message));
    }
}
