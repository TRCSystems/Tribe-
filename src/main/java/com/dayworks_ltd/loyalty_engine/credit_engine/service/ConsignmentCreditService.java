package com.dayworks_ltd.loyalty_engine.credit_engine.service;



import com.dayworks_ltd.loyalty_engine.credit_engine.exception.InsufficientCreditException;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditLimit;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.ReserveAccount;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.CreditLimitRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.ReserveAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Draw-time credit check and reservation for FINANCED orders.
 *
 * IMPORTANT: this checks the FINANCED AMOUNT (order_total - merchant_contribution),
 * NOT the gross order value. A 10,000 order with 7,000 paid by the merchant and
 * 3,000 financed only consumes 3,000 of exposure -- that distinction is the whole
 * point of this model. Do not pass gross order total here.
 *
 * Four constraints, all must pass:
 *   1. Merchant's own available credit  (approvedLimit - outstandingBalance)
 *   2. Portfolio available capital       (reserve.totalReserve - reserve.allocated)
 *   3. 50% concentration ceiling         (no single draw > half of CURRENTLY
 *                                          available pool capital -- protects
 *                                          against one transaction monopolizing
 *                                          whatever capacity remains)
 *   4. KES 35,000 hard transaction ceiling (independent of pool size -- stays
 *                                            in force even after the pool grows
 *                                            beyond the current 35k self-funded
 *                                            amount, e.g. once Paya/bank capital
 *                                            is added)
 *
 * LOCK ORDER IS NOT OPTIONAL: ReserveAccount FIRST, CreditLimit SECOND, every
 * time, everywhere in this codebase that touches both -- same rule as before.
 * Both locks are acquired up front here, before any business-rule check runs,
 * so the order in which constraints are EVALUATED doesn't affect correctness --
 * only the order they're LOCKED in matters for deadlock avoidance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsignmentCreditService {

    private final ReserveAccountRepository reserveAccountRepository;
    private final CreditLimitRepository creditLimitRepository;

    private static final String DEFAULT_SOURCE = "ASANO_SELF_FUNDED";
    private static final BigDecimal TRANSACTION_CEILING = BigDecimal.valueOf(50000);
    private static final BigDecimal CONCENTRATION_RATIO = BigDecimal.valueOf(0.3);

    @Transactional
    public void reserveCreditForFinancedOrder(Long merchantId, BigDecimal financedAmount) {
        if (financedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            // Nothing to reserve -- caller should not have invoked this for a
            // fully-cash order, but guard anyway rather than silently no-op.
            throw new IllegalArgumentException("financedAmount must be positive to reserve credit");
        }

        // 1. LOCK RESERVE ACCOUNT FIRST.
        ReserveAccount pool = reserveAccountRepository.findBySourceNameForUpdate(DEFAULT_SOURCE)
                .orElseThrow(() -> new InsufficientCreditException(
                        "Lending pool '" + DEFAULT_SOURCE + "' is not configured"));

        BigDecimal poolAvailable = pool.getTotalReserve().subtract(pool.getAllocated());

        // 2. LOCK CREDIT LIMIT SECOND.
        CreditLimit limit = creditLimitRepository.findByMerchantIdForUpdate(merchantId)
                .orElseThrow(() -> new InsufficientCreditException(
                        "Merchant " + merchantId + " has no assigned credit limit -- not eligible for financing"));

        if (limit.getApprovedLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InsufficientCreditException(
                    "Merchant " + merchantId + " is not eligible for financing (approved limit is zero)");
        }

        BigDecimal merchantAvailable = limit.getApprovedLimit().subtract(limit.getOutstandingBalance());
        BigDecimal concentrationCapacity = poolAvailable.multiply(CONCENTRATION_RATIO);

        // Determine the binding constraint so the rejection reason is actually useful.
        BigDecimal maximumFinancing = merchantAvailable
                .min(poolAvailable)
                .min(concentrationCapacity)
                .min(TRANSACTION_CEILING);

        if (financedAmount.compareTo(maximumFinancing) > 0) {
            String reason;
            if (maximumFinancing.compareTo(merchantAvailable) == 0) {
                reason = String.format("exceeds merchant %d's available credit KES %s (limit KES %s, outstanding KES %s)",
                        merchantId, merchantAvailable, limit.getApprovedLimit(), limit.getOutstandingBalance());
            } else if (maximumFinancing.compareTo(poolAvailable) == 0) {
                reason = String.format("exceeds portfolio available capital KES %s", poolAvailable);
            } else if (maximumFinancing.compareTo(concentrationCapacity) == 0) {
                reason = String.format("exceeds the 50%% concentration ceiling KES %s (half of available pool capital KES %s)",
                        concentrationCapacity, poolAvailable);
            } else {
                reason = String.format("exceeds the KES %s hard transaction ceiling", TRANSACTION_CEILING);
            }
            throw new InsufficientCreditException(String.format(
                    "Requested financing KES %s %s", financedAmount, reason));
        }

        // All checks passed -- reserve against both. Dirty checking flushes at
        // commit; both entities already managed from the findByXForUpdate calls above.
        pool.setAllocated(pool.getAllocated().add(financedAmount));
        limit.setOutstandingBalance(limit.getOutstandingBalance().add(financedAmount));

        log.info("CREDIT_RESERVED merchant={} financedAmount={} poolAllocatedAfter={} merchantOutstandingAfter={}",
                merchantId, financedAmount, pool.getAllocated(), limit.getOutstandingBalance());
    }
}