package com.dayworks_ltd.loyalty_engine.credit_engine.service;


import com.dayworks_ltd.loyalty_engine.credit_engine.exception.CreditLimitException;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditLimit;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.CreditLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Admin-driven grant/revision of a merchant's credit_limit row.
 * Does NOT touch ReserveAccount — a grant is a promise, not a pool reservation.
 * Actual exposure is only reserved at consignment draw time.
 *
 * Every write here sets tradeGrade = "MANUAL" and tradeScore = null so a
 * limit set through this service is distinguishable from one produced by
 * LimitCalculationService. Do not remove that marker — it's the only audit
 * trail distinguishing formula-driven limits from admin overrides.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditLimitAllocationService {

    private final CreditLimitRepository creditLimitRepository;

    private static final BigDecimal PORTFOLIO_CAP = BigDecimal.valueOf(50000);
    private static final String MANUAL_GRADE = "MANUAL";

    /** One-time grant. Fails if the merchant already has a limit — use reviseLimit() for changes. */
    @Transactional
    public CreditLimit allocateLimit(Long merchantId, BigDecimal approvedLimit, String actor) {
        validatePositive(approvedLimit);
        validateWithinPortfolioCap(approvedLimit);

        if (creditLimitRepository.findByMerchantId(merchantId).isPresent()) {
            throw new CreditLimitException(
                    "Merchant " + merchantId + " already has a credit limit — use reviseLimit()");
        }

        CreditLimit limit = CreditLimit.builder()
                .merchantId(merchantId)
                .approvedLimit(approvedLimit)
                .outstandingBalance(BigDecimal.ZERO)
                .tradeScore(null)
                .tradeGrade(MANUAL_GRADE)
                .build();

        try {
            limit = creditLimitRepository.saveAndFlush(limit);
        } catch (DataIntegrityViolationException e) {
            // two concurrent grants for the same merchant raced past the findByMerchantId check above
            throw new CreditLimitException(
                    "Merchant " + merchantId + " already has a credit limit (concurrent allocation)");
        }

        warnIfPortfolioOvercommitted(merchantId);
        log.info("CREDIT_LIMIT_GRANTED merchant={} limit={} actor={}", merchantId, approvedLimit, actor);
        return limit;
    }

    /** Admin override of an existing limit. Locks the row — a draw-time approval could be mutating it concurrently. */
    @Transactional
    public CreditLimit reviseLimit(Long merchantId, BigDecimal newLimit, String actor) {
        validatePositive(newLimit);
        validateWithinPortfolioCap(newLimit);

        CreditLimit limit = creditLimitRepository.findByMerchantIdForUpdate(merchantId)
                .orElseThrow(() -> new CreditLimitException("No credit limit exists for merchant " + merchantId));

        // Mirrors the DB CHECK constraint (outstanding_balance <= approved_limit) — caught here
        // first so the admin gets a real reason instead of a raw constraint-violation stack trace.
        if (newLimit.compareTo(limit.getOutstandingBalance()) < 0) {
            throw new CreditLimitException(String.format(
                    "Cannot set limit to %s for merchant %d — outstanding balance is already %s",
                    newLimit, merchantId, limit.getOutstandingBalance()));
        }

        BigDecimal oldLimit = limit.getApprovedLimit();
        limit.setApprovedLimit(newLimit);
        limit.setTradeScore(null);
        limit.setTradeGrade(MANUAL_GRADE);
        // dirty checking flushes at commit — no explicit save needed

        warnIfPortfolioOvercommitted(merchantId);
        log.info("CREDIT_LIMIT_REVISED merchant={} old={} new={} actor={}", merchantId, oldLimit, newLimit, actor);
        return limit;
    }

    private void validatePositive(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit limit must be positive");
        }
    }

    private void validateWithinPortfolioCap(BigDecimal amount) {
        if (amount.compareTo(PORTFOLIO_CAP) > 0) {
            throw new CreditLimitException("Credit limit cannot exceed the pilot portfolio cap of KES 35,000");
        }
    }

    /**
     * Non-blocking sanity check. Sum of all approved limits CAN legitimately exceed the pool —
     * a grant is a promise, not a reservation. This only logs so an admin notices the book is
     * over-promised before a merchant actually tries to draw and gets rationed by ReserveAccount.
     */
    private void warnIfPortfolioOvercommitted(Long triggeringMerchantId) {
        BigDecimal totalGranted = creditLimitRepository.findAll().stream()
                .map(CreditLimit::getApprovedLimit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalGranted.compareTo(PORTFOLIO_CAP) > 0) {
            log.warn("PORTFOLIO_OVERCOMMITTED totalGranted={} cap={} triggeredBy={}",
                    totalGranted, PORTFOLIO_CAP, triggeringMerchantId);
        }
    }
}