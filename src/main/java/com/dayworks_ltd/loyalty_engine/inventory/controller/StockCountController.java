package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.enums.VarianceReason;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockCountService;
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
import java.util.*;

/**
 * End-of-day blind stock count.
 *
 * Counting is blind for everyone at the shop: only item names and whether to count / recount.
 * System quantities, variances and the discrepancy report need ADMIN, or a MERCHANT login
 * that has been unlocked with the owner's SMS code (see StockVisibility).
 * The merchant is always taken from the logged-in user, never from the request.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory/stock-count")
@RequiredArgsConstructor
@Tag(name = "Stock Count API", description = "End-of-day blind stock count and discrepancy reporting")
public class StockCountController {

    private final StockCountService stockCountService;
    private final UserRepository userRepository;
    private final StockVisibility stockVisibility;

    @PostMapping("/start")
    @Operation(summary = "Start today's blind count",
            description = "Builds today's count queue (fastest-moving items first) or returns the one already started")
    public ResponseEntity<?> start(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, false, (user, merchantId) -> {
            CloseDay closeDay = stockCountService.startCount(merchantId, user.getUserId());
            return ok("Stock count started", sessionView(closeDay, user));
        });
    }

    @PostMapping("/remind-now")
    @Operation(summary = "Send tonight's count reminder SMS now (owner/admin)",
            description = "Prepares today's count list if needed and texts every number on the shop's list how many items to count.")
    public ResponseEntity<?> remindNow(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, false, (user, merchantId) -> {
            if (user.getRole() == UserRole.SALES_PERSON) {
                return fail(HttpStatus.FORBIDDEN, "Only the owner can send the count reminder");
            }
            int remaining = stockCountService.prepareCountAndRemind(merchantId);
            if (remaining < 0) {
                return fail(HttpStatus.CONFLICT, "Today's count is already finished");
            }
            return ok(remaining == 0 ? "Everything is already counted — no reminder needed"
                            : "Reminder sent: " + remaining + " item" + (remaining == 1 ? "" : "s") + " to count",
                    Map.of("itemsToCount", remaining));
        });
    }

    @GetMapping("/moved-today")
    @Operation(summary = "Items that moved today (sold or received), shown before the count starts",
            description = "No quantities — just which items moved, so they can be counted first.")
    public ResponseEntity<?> movedToday(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, false, (user, merchantId) -> {
            List<Map<String, Object>> moved = stockCountService.movedToday(merchantId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", stockCountService.today());
            data.put("count", moved.size());
            data.put("items", moved);
            return ok("Items moved today", data);
        });
    }

    @GetMapping("/current")
    @Operation(summary = "Get today's count queue")
    public ResponseEntity<?> current(@AuthenticationPrincipal CustomUserDetails principal) {
        return handle(principal, false, (user, merchantId) -> stockCountService
                .findSession(merchantId, stockCountService.today())
                .map(stockCountService::addMissingItems)
                .<ResponseEntity<?>>map(d -> ok("Current stock count", sessionView(d, user)))
                .orElseGet(() -> fail(HttpStatus.NOT_FOUND, "No stock count has been started today")));
    }

    @PostMapping("/lines/{lineId}/count")
    @Operation(summary = "Submit a blind count for one item",
            description = "Body: {\"quantity\": 12}. If it does not match the system, a recount is requested without revealing the system figure.")
    public ResponseEntity<?> count(@AuthenticationPrincipal CustomUserDetails principal,
                                   @PathVariable Long lineId,
                                   @RequestBody Map<String, Object> body) {
        return handle(principal, false, (user, merchantId) -> {
            Object raw = body == null ? null : body.get("quantity");
            if (raw == null) {
                return fail(HttpStatus.BAD_REQUEST, "quantity is required");
            }
            int quantity;
            try {
                quantity = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                return fail(HttpStatus.BAD_REQUEST, "quantity must be a whole number");
            }

            StockCountLine line = stockCountService.submitCount(merchantId, user.getUserId(), lineId, quantity);
            boolean recount = line.getStatus() == StockCountStatus.RECOUNT_REQUESTED;

            Map<String, Object> data = new LinkedHashMap<>(isBlind(user)
                    ? stockCountService.blindLineView(line)
                    : stockCountService.fullLineView(line));
            data.put("result", recount ? "RECOUNT" : "ACCEPTED");

            return ok(recount
                    ? "Please recount " + line.getItemName() + " carefully and submit again"
                    : "Count recorded", data);
        });
    }

    @PostMapping("/finalize")
    @Operation(summary = "Finalize today's count",
            description = "Items not counted are flagged NOT_COUNTED and the owner receives an SMS summary. Body (optional): {\"note\": \"...\"}")
    public ResponseEntity<?> finalizeCount(@AuthenticationPrincipal CustomUserDetails principal,
                                           @RequestBody(required = false) Map<String, String> body) {
        return handle(principal, false, (user, merchantId) -> {
            String note = body == null ? null : body.get("note");
            CloseDay closeDay = stockCountService.finalizeCount(merchantId, String.valueOf(user.getUserId()), note);

            Object data = isBlind(user)
                    ? Map.of("status", closeDay.getStatus(),
                             "itemsCounted", closeDay.getTotalSkusCounted(),
                             "itemsNotCounted", closeDay.getTotalSkusNotCounted())
                    : stockCountService.summaryView(closeDay);
            return ok("Stock count finalized", data);
        });
    }

    @GetMapping("/report")
    @Operation(summary = "Daily discrepancy report",
            description = "Missing and surplus items for the day, worst first, plus items that went missing on 2+ days this week. "
                    + "Once a count is finished (and SMS'd to the owner) anyone at the shop can view it; while a count is still "
                    + "open, only the owner (after SMS unlock) or ADMIN can. ADMIN may pass merchantId.")
    public ResponseEntity<?> report(@AuthenticationPrincipal CustomUserDetails principal,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(required = false) String merchantId) {
        return handle(principal, false, true, (user, ownMerchantId) -> {
            String target = resolveTargetMerchant(user, ownMerchantId, merchantId);
            LocalDate day = date != null ? date : stockCountService.today();
            CloseDay closeDay = stockCountService.findSession(target, day).orElse(null);
            if (closeDay == null) {
                return fail(HttpStatus.NOT_FOUND, "No stock count found for " + day);
            }
            // Anyone at the shop can see a finished count. Quantities are only included for
            // ADMIN or an owner who unlocked with the SMS code; everyone else sees items and values.
            if (!closeDay.isClosed()) {
                return fail(HttpStatus.CONFLICT, "Results are shown once the stock count is finished");
            }
            return ok("Stock count report for " + day, stockCountService.buildReport(closeDay, !isBlind(user)));
        });
    }

    @GetMapping("/history")
    @Operation(summary = "Daily count summaries over a date range")
    public ResponseEntity<?> history(@AuthenticationPrincipal CustomUserDetails principal,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) String merchantId) {
        return handle(principal, false, true, (user, ownMerchantId) -> {
            String target = resolveTargetMerchant(user, ownMerchantId, merchantId);
            LocalDate end = to != null ? to : stockCountService.today();
            LocalDate start = from != null ? from : end.minusDays(30);
            return ok("Stock count history", stockCountService.history(target, start, end));
        });
    }

    @PostMapping("/lines/{lineId}/classify")
    @Operation(summary = "Record the reason for a variance (owner/admin)",
            description = "Body: {\"reason\": \"LOSS\", \"note\": \"...\", \"adjustStock\": false}. "
                    + "Stock changes only when adjustStock is true.")
    public ResponseEntity<?> classify(@AuthenticationPrincipal CustomUserDetails principal,
                                      @PathVariable Long lineId,
                                      @RequestBody Map<String, Object> body) {
        return handle(principal, true, (user, merchantId) -> {
            if (merchantId == null || merchantId.isBlank()) {
                return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
            }
            VarianceReason reason;
            try {
                reason = VarianceReason.valueOf(String.valueOf(body.get("reason")).trim().toUpperCase());
            } catch (Exception e) {
                return fail(HttpStatus.BAD_REQUEST, "reason must be one of " + Arrays.toString(VarianceReason.values()));
            }
            String note = body.get("note") == null ? null : body.get("note").toString();
            boolean adjustStock = Boolean.parseBoolean(String.valueOf(body.getOrDefault("adjustStock", "false")));

            StockCountLine line = stockCountService.classifyVariance(
                    merchantId, user.getUserId(), lineId, reason, note, adjustStock);
            return ok("Variance classified", stockCountService.fullLineView(line));
        });
    }

    // ───────────────────────────── helpers ─────────────────────────────

    @FunctionalInterface
    private interface Action {
        ResponseEntity<?> run(User user, String merchantId);
    }

    private ResponseEntity<?> handle(CustomUserDetails principal, boolean ownerOnly, Action action) {
        return handle(principal, ownerOnly, ownerOnly, action);
    }

    /**
     * @param ownerOnly      needs MERCHANT/ADMIN and, for MERCHANT, the owner's SMS unlock
     * @param adminCanTarget ADMIN without a linked merchant may act (passing merchantId)
     */
    private ResponseEntity<?> handle(CustomUserDetails principal, boolean ownerOnly, boolean adminCanTarget, Action action) {
        try {
            if (principal == null) {
                return fail(HttpStatus.UNAUTHORIZED, "Authentication required");
            }
            User user = userRepository.getUserById(principal.getUserId());
            if (user == null) {
                return fail(HttpStatus.UNAUTHORIZED, "User not found");
            }
            if (ownerOnly && user.getRole() == UserRole.SALES_PERSON) {
                return fail(HttpStatus.FORBIDDEN, "Only the owner or an admin can view or review stock count results");
            }
            if (ownerOnly && isBlind(user)) {
                // Owner login, but not verified on this device — the attendant may be using it
                return fail(HttpStatus.LOCKED, StockVisibility.LOCKED_MESSAGE);
            }
            String merchantId = user.getMerchantId();
            // Only ADMIN reading reports may be unlinked (they pass merchantId instead)
            boolean adminReader = adminCanTarget && user.getRole() == UserRole.ADMIN;
            if ((merchantId == null || merchantId.isBlank()) && !adminReader) {
                return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
            }
            return action.run(user, merchantId);
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            return fail(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("Stock count request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Stock count request failed");
        }
    }

    /** ADMIN may look at any merchant; everyone else only at their own. */
    private String resolveTargetMerchant(User user, String ownMerchantId, String requested) {
        if (user.getRole() == UserRole.ADMIN && requested != null && !requested.isBlank()) {
            return requested;
        }
        if (ownMerchantId == null || ownMerchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId is required");
        }
        return ownMerchantId;
    }

    /** Everyone counts blind unless they are ADMIN or the owner has unlocked with the SMS code. */
    private boolean isBlind(User user) {
        return stockVisibility.isBlindToStock(user);
    }

    private Map<String, Object> sessionView(CloseDay closeDay, User user) {
        List<StockCountLine> lines = stockCountService.getLines(closeDay);
        Map<String, Object> view = new LinkedHashMap<>();
        if (isBlind(user)) {
            view.put("closeDayId", closeDay.getId());
            view.put("tradingDate", closeDay.getTradingDate());
            view.put("status", closeDay.getStatus());
            view.put("itemsToCount", lines.size());
            view.put("itemsRemaining", lines.stream()
                    .filter(l -> l.getStatus() == StockCountStatus.PENDING
                            || l.getStatus() == StockCountStatus.RECOUNT_REQUESTED)
                    .count());
            view.put("lines", lines.stream().map(stockCountService::blindLineView).toList());
        } else {
            view.putAll(stockCountService.summaryView(closeDay));
            view.put("lines", lines.stream().map(stockCountService::fullLineView).toList());
        }
        return view;
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
