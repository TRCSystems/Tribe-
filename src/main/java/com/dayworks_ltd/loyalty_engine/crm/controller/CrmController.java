package com.dayworks_ltd.loyalty_engine.crm.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmMessage;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmMessageRepository;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmContactService;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmMessagingService;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmShopService;
import com.dayworks_ltd.loyalty_engine.crm.services.CrmStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * CRM foundation API for distributors and their field reps.
 * The distributor is always the logged-in user's merchant (ADMIN may pass distributorId).
 * Field reps can read their own shops and message them; managing reps and assignments is distributor-only.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/crm")
@RequiredArgsConstructor
@Tag(name = "CRM API", description = "Distributor shops, field reps, contacts and messages")
public class CrmController {

    private final CrmShopService shopService;
    private final CrmContactService contactService;
    private final CrmMessagingService messagingService;
    private final CrmMessageRepository messageRepository;
    private final CrmStatsService statsService;
    private final UserRepository userRepository;

    // ───────────────────────────── shops ─────────────────────────────

    @GetMapping("/shops")
    @Operation(summary = "Distributor's shops with order counts, last order, rep and contact preferences")
    public ResponseEntity<?> shops(@AuthenticationPrincipal CustomUserDetails principal,
                                   @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, false, (user, dist) -> ok("Shops", shopService.listShops(dist, user)));
    }

    @GetMapping("/shops/{shopId}")
    @Operation(summary = "Shop profile: segment, buying rhythm, top and cross-sell products, recent orders")
    public ResponseEntity<?> shopProfile(@AuthenticationPrincipal CustomUserDetails principal,
                                         @PathVariable Long shopId,
                                         @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, false, (user, dist) -> {
            requireShop(dist, shopId);
            requireRepShop(user, dist, shopId);
            return ok("Shop profile", shopService.shopProfile(dist, shopId, user));
        });
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Headline numbers (orders, basket, frequency, self-ordering) and segment counts")
    public ResponseEntity<?> dashboard(@AuthenticationPrincipal CustomUserDetails principal,
                                       @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, true, (user, dist) -> ok("CRM dashboard", statsService.dashboard(dist)));
    }

    @PostMapping("/stats/refresh")
    @Operation(summary = "Recompute shop stats and segments now (they also refresh nightly at 02:30)")
    public ResponseEntity<?> refreshStats(@AuthenticationPrincipal CustomUserDetails principal,
                                          @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, true, (user, dist) ->
                ok("Stats refreshed", Map.of("shops", statsService.refresh(dist).size())));
    }

    @PutMapping("/shops/{shopId}/rep")
    @Operation(summary = "Assign a field rep to a shop", description = "Body: {\"repUserId\": 12} or {\"repUserId\": null} to unassign")
    public ResponseEntity<?> assignRep(@AuthenticationPrincipal CustomUserDetails principal,
                                       @PathVariable Long shopId,
                                       @RequestParam(required = false) Long distributorId,
                                       @RequestBody Map<String, Object> body) {
        return handle(principal, distributorId, true, (user, dist) -> {
            Object rep = body == null ? null : body.get("repUserId");
            shopService.assignRep(dist, shopId, rep == null ? null : Long.valueOf(rep.toString()), user.getUserId());
            return ok(rep == null ? "Rep unassigned" : "Rep assigned", null);
        });
    }

    @PutMapping("/shops/{shopId}/contact")
    @Operation(summary = "Update a shop's phone, WhatsApp number, preferred channel or marketing opt-out",
            description = "Body: {\"phone\": \"0712345678\", \"whatsappNumber\": \"...\", \"preferredChannel\": \"WHATSAPP\", \"marketingOptOut\": false}")
    public ResponseEntity<?> updateContact(@AuthenticationPrincipal CustomUserDetails principal,
                                           @PathVariable Long shopId,
                                           @RequestParam(required = false) Long distributorId,
                                           @RequestBody Map<String, Object> body) {
        return handle(principal, distributorId, true, (user, dist) -> {
            requireShop(dist, shopId);
            CrmEnums.Channel channel = body.get("preferredChannel") == null ? null
                    : CrmEnums.Channel.valueOf(body.get("preferredChannel").toString().trim().toUpperCase());
            CrmContact c = contactService.update(dist, shopId,
                    str(body.get("phone")), str(body.get("whatsappNumber")), channel,
                    body.get("marketingOptOut") == null ? null : Boolean.valueOf(body.get("marketingOptOut").toString()));
            return ok("Contact updated", contactView(c));
        });
    }

    @GetMapping("/shops/{shopId}/messages")
    @Operation(summary = "Messages sent (or skipped) to one shop, newest first")
    public ResponseEntity<?> shopMessages(@AuthenticationPrincipal CustomUserDetails principal,
                                          @PathVariable Long shopId,
                                          @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, false, (user, dist) -> {
            requireShop(dist, shopId);
            requireRepShop(user, dist, shopId);
            return ok("Messages", messageRepository
                    .findByDistributorIdAndShopMerchantIdOrderByCreatedAtDesc(dist, shopId, PageRequest.of(0, 100))
                    .stream().map(CrmController::messageView).toList());
        });
    }

    @PostMapping("/shops/{shopId}/messages")
    @Operation(summary = "Send a template (or a custom note) to one shop",
            description = "Body: {\"template\": \"SELF_ORDER_INVITE\"} or {\"template\": \"CUSTOM\", \"text\": \"...\"}. "
                    + "Marketing rules apply: opt-out, hold-out group, weekly limit, quiet hours.")
    public ResponseEntity<?> sendToShop(@AuthenticationPrincipal CustomUserDetails principal,
                                        @PathVariable Long shopId,
                                        @RequestParam(required = false) Long distributorId,
                                        @RequestBody Map<String, Object> body) {
        return handle(principal, distributorId, false, (user, dist) -> {
            requireShop(dist, shopId);
            requireRepShop(user, dist, shopId);
            CrmTemplate template = CrmTemplate.valueOf(String.valueOf(body.get("template")).trim().toUpperCase());
            Map<String, String> vars = new HashMap<>();
            if (body.get("text") != null) vars.put("text", body.get("text").toString());
            if (body.get("items") != null) vars.put("items", body.get("items").toString());
            if (body.get("link") != null) vars.put("link", body.get("link").toString());
            CrmMessage m = messagingService.send(dist, shopId, template, vars, "MANUAL", user.getUserId());
            String msg = switch (m.getStatus()) {
                case SENT -> "Message sent";
                case FAILED -> "The SMS provider did not accept the message";
                case SKIPPED -> "Not sent: " + m.getSkipReason();
            };
            return ok(msg, messageView(m));
        });
    }

    @GetMapping("/messages")
    @Operation(summary = "Latest messages across all shops (distributor)")
    public ResponseEntity<?> messages(@AuthenticationPrincipal CustomUserDetails principal,
                                      @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, true, (user, dist) -> ok("Messages", messageRepository
                .findByDistributorIdOrderByCreatedAtDesc(dist, PageRequest.of(0, 200))
                .stream().map(CrmController::messageView).toList()));
    }

    @GetMapping("/templates")
    @Operation(summary = "The fixed message library")
    public ResponseEntity<?> templates(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, null, false, (user, dist) -> ok("Templates", Arrays.stream(CrmTemplate.values())
                .map(t -> Map.of("key", t.name(), "marketing", t.isMarketing(),
                        "preview", t.render(Map.of("shop", "Mama Njeri Wines", "distributor", "Asano",
                                "items", "5x County 1/4, 2x Kibao 1/4", "link", "https://tribessystems.co.ke/o/abc123",
                                "count", "#123456", "text", "Your message here", "walink", "https://wa.me/254700000000?text=Order"))))
                .toList()));
    }

    // ───────────────────────────── reps ─────────────────────────────

    @GetMapping("/reps")
    @Operation(summary = "The distributor's field reps and how many shops each looks after")
    public ResponseEntity<?> reps(@AuthenticationPrincipal CustomUserDetails principal,
                                  @RequestParam(required = false) Long distributorId) {
        return handle(principal, distributorId, true, (user, dist) -> ok("Field reps", shopService.listReps(dist)));
    }

    @PostMapping("/reps")
    @Operation(summary = "Create a field rep login (for the SFA app)", description = "Body: {\"username\": \"john.k\", \"password\": \"...\"}")
    public ResponseEntity<?> createRep(@AuthenticationPrincipal CustomUserDetails principal,
                                       @RequestParam(required = false) Long distributorId,
                                       @RequestBody Map<String, String> body) {
        return handle(principal, distributorId, true, (user, dist) ->
                ok("Field rep created", shopService.createRep(dist, body.get("username"), body.get("password"))));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    @FunctionalInterface
    private interface Action {
        ResponseEntity<?> run(User user, Long distributorId);
    }

    /**
     * @param distributorOnly true for actions a field rep may not do (managing reps, assignments, contacts)
     */
    private ResponseEntity<?> handle(CustomUserDetails principal, Long requestedDistributorId,
                                     boolean distributorOnly, Action action) {
        try {
            if (principal == null) return fail(HttpStatus.UNAUTHORIZED, "Authentication required");
            User user = userRepository.getUserById(principal.getUserId());
            if (user == null) return fail(HttpStatus.UNAUTHORIZED, "User not found");
            if (distributorOnly && user.getRole() == UserRole.FIELD_REP) {
                return fail(HttpStatus.FORBIDDEN, "Only the distributor can do this");
            }
            Long dist = user.getRole() == UserRole.ADMIN && requestedDistributorId != null
                    ? requestedDistributorId
                    : (user.getMerchantId() == null ? null : Long.valueOf(user.getMerchantId()));
            if (dist == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to a distributor");
            return action.run(user, dist);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return fail(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("CRM request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "CRM request failed");
        }
    }

    private void requireShop(Long distributorId, Long shopId) {
        if (!shopService.isDistributorShop(distributorId, shopId)) {
            throw new IllegalArgumentException("Shop not found for this distributor");
        }
    }

    /** Field reps may only act on shops assigned to them. */
    private void requireRepShop(User user, Long distributorId, Long shopId) {
        if (user.getRole() != UserRole.FIELD_REP) return;
        boolean mine = shopService.listShops(distributorId, user).stream().anyMatch(s -> shopId.equals(s.get("shopId")));
        if (!mine) throw new IllegalArgumentException("This shop is not assigned to you");
    }

    private static Map<String, Object> contactView(CrmContact c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopId", c.getShopMerchantId());
        m.put("phone", c.getPhone());
        m.put("whatsappNumber", c.getWhatsappNumber());
        m.put("preferredChannel", c.getPreferredChannel());
        m.put("marketingOptOut", c.getMarketingOptOut());
        m.put("optOutAt", c.getOptOutAt());
        return m;
    }

    private static Map<String, Object> messageView(CrmMessage m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId());
        v.put("shopId", m.getShopMerchantId());
        v.put("template", m.getTemplate());
        v.put("campaignKey", m.getCampaignKey());
        v.put("marketing", m.getMarketing());
        v.put("channel", m.getChannel());
        v.put("toNumber", m.getToNumber());
        v.put("body", m.getBody());
        v.put("status", m.getStatus());
        v.put("skipReason", m.getSkipReason());
        v.put("sentByUserId", m.getSentByUserId());
        v.put("createdAt", m.getCreatedAt());
        return v;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
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
