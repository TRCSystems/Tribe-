package com.dayworks_ltd.loyalty_engine.crm.controller;

import com.dayworks_ltd.loyalty_engine.crm.services.ShopOrderService;
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
 * Public shop ordering page API — no login; access is the one-time token in the SMS/WhatsApp link.
 * Never answers 401 (the web app would treat that as a logged-out session).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shop-order")
@RequiredArgsConstructor
@Tag(name = "Shop Order (public link)", description = "Order page opened from a CRM message link")
public class ShopOrderController {

    private final ShopOrderService shopOrderService;

    @GetMapping("/{token}")
    @Operation(summary = "Catalogue, usual order and link status for the order page")
    public ResponseEntity<?> view(@PathVariable String token) {
        return handle(() -> shopOrderService.view(token));
    }

    @PostMapping("/{token}/orders")
    @Operation(summary = "Place the order (pay on delivery)",
            description = "Body: {\"items\":[{\"itemCode\":\"23-ABC\",\"quantity\":5}], \"extras\":[{\"name\":\"Guinness 500ml\",\"quantity\":2}]}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> place(@PathVariable String token, @RequestBody Map<String, Object> body) {
        return handle(() -> {
            List<ShopOrderService.ItemQty> items = new ArrayList<>();
            for (Map<String, Object> i : (List<Map<String, Object>>) body.getOrDefault("items", List.of())) {
                items.add(new ShopOrderService.ItemQty(String.valueOf(i.get("itemCode")), toInt(i.get("quantity"))));
            }
            List<ShopOrderService.Extra> extras = new ArrayList<>();
            for (Map<String, Object> e : (List<Map<String, Object>>) body.getOrDefault("extras", List.of())) {
                extras.add(new ShopOrderService.Extra(e.get("name") == null ? null : e.get("name").toString(), toInt(e.get("quantity"))));
            }
            Object phone = body.get("phone");
            return shopOrderService.placeOrder(token, items, extras, phone == null ? null : phone.toString());
        });
    }

    @PostMapping("/{token}/pay")
    @Operation(summary = "Send an M-Pesa prompt for the order placed with this link", description = "Body (optional): {\"phone\": \"0712345678\"}")
    public ResponseEntity<?> pay(@PathVariable String token, @RequestBody(required = false) Map<String, String> body) {
        return handle(() -> shopOrderService.pay(token, body == null ? null : body.get("phone")));
    }

    private ResponseEntity<?> handle(Supplier<Object> action) {
        try {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "SUCCESS");
            ok.put("data", action.get());
            return ResponseEntity.ok(ok);
        } catch (NoSuchElementException e) {
            return fail(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            return fail(HttpStatus.GONE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Shop order request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again or call your rep.");
        }
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(o.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(),
                "message", message == null ? "Request failed" : message));
    }
}
