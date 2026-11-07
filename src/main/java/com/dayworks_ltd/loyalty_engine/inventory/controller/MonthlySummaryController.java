package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.SalesSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/** Month summary for the owner: preview it any time, or send it now (it also goes out automatically at month end). */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory/reports/monthly-summary")
@RequiredArgsConstructor
@Tag(name = "Monthly Summary API", description = "What the shop sold, made and closed with each month")
public class MonthlySummaryController {

    private final SalesSummaryService summaryService;
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "Preview a month's summary (defaults to the current month)", description = "month=2026-09")
    public ResponseEntity<?> preview(@AuthenticationPrincipal CustomUserDetails principal,
                                     @RequestParam(required = false) String month) {
        return handle(principal, (merchantId) -> {
            YearMonth ym = month == null ? YearMonth.now() : YearMonth.parse(month);
            SalesSummaryService.Summary s = summaryService.summary(merchantId, ym.atDay(1), ym.atEndOfMonth());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("month", ym.toString());
            d.put("sales", s.sales());
            d.put("revenueKes", s.revenue());
            d.put("marginKes", s.margin());
            d.put("marginPct", s.marginPct());
            d.put("linesWithoutCost", s.linesWithoutCost());
            d.put("closingStockKes", summaryService.closingStockValue(merchantId));
            return ok("Monthly summary", d);
        });
    }

    @PostMapping("/send")
    @Operation(summary = "Send a month's summary SMS to the owner's numbers now", description = "month=2026-09")
    public ResponseEntity<?> send(@AuthenticationPrincipal CustomUserDetails principal,
                                  @RequestParam(required = false) String month) {
        return handle(principal, (merchantId) -> {
            YearMonth ym = month == null ? YearMonth.now() : YearMonth.parse(month);
            return ok("Summary sent", Map.of("message", summaryService.sendMonthlySummary(merchantId, ym)));
        });
    }

    @FunctionalInterface
    private interface Action {
        ResponseEntity<?> run(String merchantId);
    }

    private ResponseEntity<?> handle(CustomUserDetails principal, Action action) {
        try {
            if (principal == null) return fail(HttpStatus.UNAUTHORIZED, "Authentication required");
            User user = userRepository.getUserById(principal.getUserId());
            if (user == null) return fail(HttpStatus.UNAUTHORIZED, "User not found");
            if (user.getRole() == UserRole.SALES_PERSON || user.getRole() == UserRole.FIELD_REP) {
                return fail(HttpStatus.FORBIDDEN, "Only the owner can see the monthly summary");
            }
            if (user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to a shop");
            return action.run(user.getMerchantId());
        } catch (java.time.format.DateTimeParseException e) {
            return fail(HttpStatus.BAD_REQUEST, "month must look like 2026-09");
        } catch (Exception e) {
            log.error("Monthly summary request failed", e);
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "Monthly summary request failed");
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
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(), "message", message));
    }
}
