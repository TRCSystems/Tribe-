package com.dayworks_ltd.loyalty_engine.credit_engine.controller;


import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.AllocateLimitRequest;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.CreditLimitResponse;
import com.dayworks_ltd.loyalty_engine.credit_engine.exception.CreditLimitException;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditLimit;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.CreditLimitRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.CreditLimitAllocationService;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.LimitCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/credit-limits")
@RequiredArgsConstructor
@Slf4j
public class CreditLimitController {

    private final CreditLimitRepository creditLimitRepository;
    private final CreditLimitAllocationService allocationService;
    private final LimitCalculationService calculationService;

    @GetMapping("/{merchantId}")
    public ResponseEntity<CreditLimitResponse> get(@PathVariable Long merchantId) {
        CreditLimit limit = creditLimitRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new CreditLimitException("No credit limit exists for merchant " + merchantId));
        return ResponseEntity.ok(toResponse(limit));
    }

    /** Data-driven: runs the scoring formula off order history. Preferred path. */
    @PostMapping("/{merchantId}/recalculate")
    public ResponseEntity<CreditLimitResponse> recalculate(
            @PathVariable Long merchantId,
            Authentication authentication) {
        String actor = resolveActor(authentication);
        CreditLimit limit = calculationService.calculateAndAssign(merchantId, actor);
        return ResponseEntity.ok(toResponse(limit));
    }

    @PostMapping("/recalculate-all")
    public ResponseEntity<Void> recalculateAll(Authentication authentication) {
        String actor = resolveActor(authentication);
        calculationService.recalculateAll(actor);
        return ResponseEntity.accepted().build();
    }

    /** Manual override — bypasses scoring. Every call here is an admin decision, not a formula. */
    @PostMapping("/{merchantId}/manual")
    public ResponseEntity<CreditLimitResponse> manualAllocate(
            @PathVariable Long merchantId,
            @RequestBody AllocateLimitRequest request,
            Authentication authentication) {
        String actor = resolveActor(authentication);
        CreditLimit limit = creditLimitRepository.findByMerchantId(merchantId).isPresent()
                ? allocationService.reviseLimit(merchantId, request.getApprovedLimit(), actor)
                : allocationService.allocateLimit(merchantId, request.getApprovedLimit(), actor);
        return ResponseEntity.ok(toResponse(limit));
    }

    @ExceptionHandler(CreditLimitException.class)
    public ResponseEntity<String> handleCreditLimitException(CreditLimitException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }

    /**
     * Resolves the acting user for audit logging (CREDIT_LIMIT_GRANTED/REVISED/CALCULATED actor=...).
     * Never logs the principal's password or any other credential — only identifiers.
     */
    private String resolveActor(Authentication authentication) {
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        log.info("CREDIT_LIMIT_ACTION userId={} username={} role={}",
                principal.getUserId(), principal.getUsername(), principal.getUserRole());
        return principal.getUsername();
    }

    private CreditLimitResponse toResponse(CreditLimit limit) {
        BigDecimal outstanding = limit.getOutstandingBalance() != null
                ? limit.getOutstandingBalance()
                : BigDecimal.ZERO;

        BigDecimal approved = limit.getApprovedLimit() != null
                ? limit.getApprovedLimit()
                : BigDecimal.ZERO;

        // Storage stays positive; only the response is negated
        BigDecimal responseOutstanding = outstanding.negate();

        // Any outstanding → availableLimit = full outstanding as negative
        // No outstanding  → availableLimit = full approved limit
        BigDecimal available = outstanding.compareTo(BigDecimal.ZERO) > 0
                ? responseOutstanding
                : approved;

        return CreditLimitResponse.builder()
                .merchantId(limit.getMerchantId())
                .approvedLimit(approved)
                .outstandingBalance(responseOutstanding)
                .availableLimit(available)
                .tradeScore(limit.getTradeScore())
                .tradeGrade(limit.getTradeGrade())
                .lastRecalculatedAt(limit.getLastRecalculatedAt())
                .build();
    }
}