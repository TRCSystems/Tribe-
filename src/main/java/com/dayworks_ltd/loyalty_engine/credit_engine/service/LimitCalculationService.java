package com.dayworks_ltd.loyalty_engine.credit_engine.service;

import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditLimit;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.CreditLimitRepository;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formula-driven limit assignment off order history (no repayment history exists
 * yet -- consignment hasn't shipped -- so this scores pre-consignment order behavior
 * as a proxy for creditworthiness. Unvalidated proxy; revisit once real consignment
 * repayment data exists).
 *
 * DETERMINATION ORDER (per business requirement):
 *   1. Order volume  -> primary gate, sets the base tier (GOLD/SILVER/BRONZE)
 *   2. Tenure        -> secondary adjustment within the tier
 *   3. Order count   -> tertiary adjustment within the tier
 *
 * TENURE/COUNT COMBINATION -- FIXED 2026-08-11:
 *   Previous version multiplied tenureMultiplier x countMultiplier, both of which
 *   ramped from 0 at the eligibility gate to 1.0 at an arbitrary "full credit"
 *   point. Multiplying two suppressed fractions compounds the suppression instead
 *   of averaging it -- a merchant who JUST cleared both gates kept under 10% of
 *   their tier value, regardless of how strong their volume was. Since gates
 *   already establish legitimacy, each multiplier now ramps from a FLOOR (not
 *   zero) at the gate up to 1.0, and the two are AVERAGED, not multiplied, so one
 *   weak axis can't crush the other.
 *
 * PORTFOLIO AWARENESS:
 *   Every eligible merchant's raw entitlement is computed independently (order-
 *   independent -- no merchant's number depends on another's). If the sum of all
 *   raw entitlements exceeds the 35k pool, every merchant is scaled down by the
 *   SAME factor -- proportional normalization, not first-scored-wins.
 */
@Service
@Slf4j
public class LimitCalculationService {

    private final OrderRepository orderRepository;
    private final CreditLimitRepository creditLimitRepository;

    /**
     * Self-injected proxy reference. Any call from inside this class to a
     * @Transactional method on this class MUST go through `self`, not a bare
     * `this.method(...)` call -- a same-class call bypasses Spring's CGLIB proxy,
     * silently ignoring @Transactional and breaking row-level locking.
     */
    private final LimitCalculationService self;

    public LimitCalculationService(
            OrderRepository orderRepository,
            CreditLimitRepository creditLimitRepository,
            @Lazy LimitCalculationService self) {
        this.orderRepository = orderRepository;
        this.creditLimitRepository = creditLimitRepository;
        this.self = self;
    }

    private static final BigDecimal PORTFOLIO_CAP    = BigDecimal.valueOf(50000);
    private static final BigDecimal POOL_SHARE_RATIO = BigDecimal.valueOf(0.15);
    private static final BigDecimal PER_MERCHANT_CAP =
            PORTFOLIO_CAP.multiply(POOL_SHARE_RATIO); // 35000 x 0.15 = 5,250 -- ceiling for a maximally-qualified merchant, BEFORE portfolio normalization

    // Eligibility gates.
    private static final int MIN_ORDER_COUNT = 10;
    private static final int MIN_TENURE_DAYS = 30;

    // --- 1. VOLUME TIERS (primary determinant) -----------------------------
    // Thresholds and fractions below are placeholders to make the formula
    // compile and rank-order correctly. Replace with real figures from your
    // merchant volume distribution -- these are NOT derived from your data.
    private static final BigDecimal VOLUME_TIER_GOLD_MIN   = BigDecimal.valueOf(150000);
    private static final BigDecimal VOLUME_TIER_SILVER_MIN = BigDecimal.valueOf(100000);
    private static final BigDecimal VOLUME_TIER_BRONZE_MIN = BigDecimal.valueOf(50000);

    private static final BigDecimal GOLD_FRACTION   = BigDecimal.valueOf(1.00);
    private static final BigDecimal SILVER_FRACTION = BigDecimal.valueOf(0.70);
    private static final BigDecimal BRONZE_FRACTION = BigDecimal.valueOf(0.40);

    // --- 2 & 3. TENURE / COUNT ramps (secondary, tertiary) -----------------
    // FLOOR = what a merchant gets the moment they clear the gate (not zero).
    // "Full credit" point = where the ramp reaches 1.0. All four numbers are
    // placeholders -- chosen so a brand-new pilot cohort (all merchants ~30
    // days old right now) doesn't get crushed to near-zero, but they are NOT
    // derived from your actual repayment or risk data. Revisit deliberately.
    private static final double TENURE_FLOOR_FRACTION     = 0.75;
    private static final double TENURE_FULL_CREDIT_DAYS   = 90;   // ramp reaches 1.0 at this tenure
    private static final double COUNT_FLOOR_FRACTION      = 0.75;
    private static final double ORDER_COUNT_FULL_CREDIT   = 30;   // ramp reaches 1.0 at this order count

    /** Result of assessing one merchant, before portfolio-wide normalization. */
    private record RawAssessment(boolean eligible, BigDecimal rawLimit, Integer diagnosticScore, String tierLabel) {
        static RawAssessment ineligible() {
            return new RawAssessment(false, BigDecimal.ZERO, null, "NEW");
        }
    }

    /**
     * Single-merchant entrypoint. To compute a portfolio-normalized limit for ONE
     * merchant, this still assesses every OTHER eligible merchant to get the
     * correct scaling factor -- same O(n) cost as recalculateAll(). Fine at pilot
     * scale; revisit if this ever runs per-request against a large merchant book.
     */
    @Transactional
    public CreditLimit calculateAndAssign(Long merchantId, String actor) {
        Map<Long, RawAssessment> assessments = assessAllMerchants();
        assessments.putIfAbsent(merchantId, assessMerchant(merchantId));

        BigDecimal scalingFactor = computeScalingFactor(assessments);
        RawAssessment a = assessments.get(merchantId);
        BigDecimal effectiveLimit = a.eligible()
                ? a.rawLimit().multiply(scalingFactor).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        if (scalingFactor.compareTo(BigDecimal.ONE) < 0) {
            log.warn("CREDIT_LIMIT_SCALED merchant={} rawLimit={} scalingFactor={} effectiveLimit={}",
                    merchantId, a.rawLimit(), scalingFactor, effectiveLimit);
        }

        return upsert(merchantId, effectiveLimit, a.diagnosticScore(), a.tierLabel(), actor);
    }

    /**
     * Batch entrypoint -- the authoritative path. Assesses every merchant once,
     * derives ONE scaling factor for the whole portfolio, applies it to everyone
     * in the same pass. Each merchant's persistence is its own transaction (via
     * self.persistAssessment) so one merchant's failure can't roll back another's
     * already-committed row.
     */
    public void recalculateAll(String actor) {
        Map<Long, RawAssessment> assessments = assessAllMerchants();
        BigDecimal scalingFactor = computeScalingFactor(assessments);

        if (scalingFactor.compareTo(BigDecimal.ONE) < 0) {
            BigDecimal totalRawDemand = sumRawDemand(assessments);
            log.warn("PORTFOLIO_NORMALIZED totalRawDemand={} cap={} scalingFactor={}",
                    totalRawDemand, PORTFOLIO_CAP, scalingFactor);
        }

        for (Map.Entry<Long, RawAssessment> entry : assessments.entrySet()) {
            try {
                Long merchantId = entry.getKey();
                RawAssessment a = entry.getValue();
                BigDecimal effectiveLimit = a.eligible()
                        ? a.rawLimit().multiply(scalingFactor).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                self.persistAssessment(merchantId, effectiveLimit, a.diagnosticScore(), a.tierLabel(), actor);
            } catch (Exception e) {
                log.error("CREDIT_LIMIT_RECALC_FAILED merchant={}", entry.getKey(), e);
            }
        }
    }

    @Transactional
    public CreditLimit persistAssessment(Long merchantId, BigDecimal effectiveLimit, Integer score, String grade, String actor) {
        return upsert(merchantId, effectiveLimit, score, grade, actor);
    }

    // ------------------------------------------------------------------
    // Assessment (order-independent -- no merchant's raw number depends on
    // any other merchant's data)
    // ------------------------------------------------------------------

    private Map<Long, RawAssessment> assessAllMerchants() {
        List<Long> merchantIds = orderRepository.findDistinctMerchantIds();
        Map<Long, RawAssessment> assessments = new LinkedHashMap<>();
        for (Long merchantId : merchantIds) {
            try {
                assessments.put(merchantId, assessMerchant(merchantId));
            } catch (Exception e) {
                log.error("CREDIT_LIMIT_ASSESS_FAILED merchant={}", merchantId, e);
                assessments.put(merchantId, RawAssessment.ineligible());
            }
        }
        return assessments;
    }

    private RawAssessment assessMerchant(Long merchantId) {
        long orderCount = orderRepository.countOrdersByMerchant(merchantId);
        BigDecimal paidVolume = orderRepository.sumPaidVolumeByMerchant(merchantId);
        LocalDateTime firstOrderDate = orderRepository.findFirstOrderDate(merchantId).orElse(null);

        // Calendar-date tenure, matching DATEDIFF semantics -- NOT Duration.between(),
        // which truncates by elapsed 24h periods and undercounts by one day whenever
        // the first order's time-of-day is later than the time this method runs.
        long tenureDays = firstOrderDate == null
                ? 0
                : ChronoUnit.DAYS.between(firstOrderDate.toLocalDate(), LocalDateTime.now().toLocalDate());

        // 1. VOLUME -- primary gate. Below bronze minimum, ineligible regardless
        //    of tenure or order count, no matter how good those numbers look.
        BigDecimal tierFraction = volumeTierFraction(paidVolume);

        boolean eligible = tierFraction.compareTo(BigDecimal.ZERO) > 0
                && orderCount >= MIN_ORDER_COUNT
                && tenureDays >= MIN_TENURE_DAYS;

        if (!eligible) {
            log.info("CREDIT_LIMIT_INELIGIBLE merchant={} paidVolume={} orderCount={} tenureDays={}",
                    merchantId, paidVolume, orderCount, tenureDays);
            return RawAssessment.ineligible();
        }

        // 2. TENURE -- ramps from TENURE_FLOOR_FRACTION at the gate to 1.0 at
        //    TENURE_FULL_CREDIT_DAYS. Does NOT start at zero -- clearing the gate
        //    already means something.
        BigDecimal tenureMultiplier = rampMultiplier(
                tenureDays, MIN_TENURE_DAYS, TENURE_FULL_CREDIT_DAYS, TENURE_FLOOR_FRACTION);

        // 3. ORDER COUNT -- same ramp shape, independent axis.
        BigDecimal countMultiplier = rampMultiplier(
                orderCount, MIN_ORDER_COUNT, ORDER_COUNT_FULL_CREDIT, COUNT_FLOOR_FRACTION);

        // AVERAGED, not multiplied -- see class-level comment on why multiplying
        // two suppressed fractions was the bug.
        BigDecimal qualifyingMultiplier = tenureMultiplier.add(countMultiplier)
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);

        BigDecimal rawLimit = PER_MERCHANT_CAP
                .multiply(tierFraction)
                .multiply(qualifyingMultiplier)
                .setScale(2, RoundingMode.HALF_UP);

        int diagnosticScore = tierFraction.multiply(qualifyingMultiplier)
                .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();

        String tierLabel = tierLabelFor(paidVolume);

        return new RawAssessment(true, rawLimit, diagnosticScore, tierLabel);
    }

    /**
     * Linear ramp from `floorFraction` at `gateValue` up to 1.0 at `fullCreditValue`.
     * Below the gate: callers never invoke this (eligibility already filtered it
     * out). At or above fullCreditValue: capped at 1.0.
     */
    private BigDecimal rampMultiplier(double value, double gateValue, double fullCreditValue, double floorFraction) {
        if (value <= gateValue) {
            return BigDecimal.valueOf(floorFraction);
        }
        if (value >= fullCreditValue) {
            return BigDecimal.ONE;
        }
        double progress = (value - gateValue) / (fullCreditValue - gateValue);
        double fraction = floorFraction + progress * (1.0 - floorFraction);
        return BigDecimal.valueOf(fraction);
    }

    private BigDecimal volumeTierFraction(BigDecimal paidVolume) {
        if (paidVolume.compareTo(VOLUME_TIER_GOLD_MIN) >= 0)   return GOLD_FRACTION;
        if (paidVolume.compareTo(VOLUME_TIER_SILVER_MIN) >= 0) return SILVER_FRACTION;
        if (paidVolume.compareTo(VOLUME_TIER_BRONZE_MIN) >= 0) return BRONZE_FRACTION;
        return BigDecimal.ZERO;
    }

    private String tierLabelFor(BigDecimal paidVolume) {
        if (paidVolume.compareTo(VOLUME_TIER_GOLD_MIN) >= 0)   return "GOLD";
        if (paidVolume.compareTo(VOLUME_TIER_SILVER_MIN) >= 0) return "SILVER";
        if (paidVolume.compareTo(VOLUME_TIER_BRONZE_MIN) >= 0) return "BRONZE";
        return "NEW";
    }

    // ------------------------------------------------------------------
    // Portfolio-wide normalization
    // ------------------------------------------------------------------

    private BigDecimal sumRawDemand(Map<Long, RawAssessment> assessments) {
        return assessments.values().stream()
                .filter(RawAssessment::eligible)
                .map(RawAssessment::rawLimit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal computeScalingFactor(Map<Long, RawAssessment> assessments) {
        BigDecimal totalRawDemand = sumRawDemand(assessments);
        return totalRawDemand.compareTo(PORTFOLIO_CAP) > 0
                ? PORTFOLIO_CAP.divide(totalRawDemand, 8, RoundingMode.HALF_UP)
                : BigDecimal.ONE;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private CreditLimit upsert(Long merchantId, BigDecimal limit, Integer score, String grade, String actor) {
        CreditLimit existing = creditLimitRepository.findByMerchantIdForUpdate(merchantId).orElse(null);

        if (existing == null) {
            CreditLimit created = creditLimitRepository.saveAndFlush(CreditLimit.builder()
                    .merchantId(merchantId)
                    .approvedLimit(limit)
                    .outstandingBalance(BigDecimal.ZERO)
                    .tradeScore(score)
                    .tradeGrade(grade)
                    .lastRecalculatedAt(LocalDateTime.now())
                    .build());
            log.info("CREDIT_LIMIT_CALCULATED merchant={} limit={} score={} tier={} actor={}",
                    merchantId, limit, score, grade, actor);
            return created;
        }

        if (limit.compareTo(existing.getOutstandingBalance()) < 0) {
            log.warn("CREDIT_LIMIT_RECALC_HELD merchant={} newLimit={} outstanding={} -- limit unchanged, tier refreshed",
                    merchantId, limit, existing.getOutstandingBalance());
            existing.setTradeScore(score);
            existing.setTradeGrade(grade);
            existing.setLastRecalculatedAt(LocalDateTime.now());
            return existing;
        }

        existing.setApprovedLimit(limit);
        existing.setTradeScore(score);
        existing.setTradeGrade(grade);
        existing.setLastRecalculatedAt(LocalDateTime.now());
        log.info("CREDIT_LIMIT_RECALCULATED merchant={} limit={} score={} tier={} actor={}",
                merchantId, limit, score, grade, actor);
        return existing;
    }
}