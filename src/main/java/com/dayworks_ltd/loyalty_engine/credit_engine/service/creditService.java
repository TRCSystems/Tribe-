package com.dayworks_ltd.loyalty_engine.credit_engine.service;



import com.dayworks_ltd.loyalty_engine.credit_engine.exception.CreditLimitExceededException;
import com.dayworks_ltd.loyalty_engine.credit_engine.exception.ReserveCapacityExceededException;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditLimit;
import com.dayworks_ltd.loyalty_engine.common.LedgerEntryType;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.ReserveAccount;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.TradeLedgerEntry;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.CreditLimitRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.ReserveAccountRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.repository.TradeLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Owns CreditLimit and ReserveAccount state. Pure DB reads/writes only —
 * never calls out to Safaricom or any external system. PaymentService calls
 * back into this after a payment lands; this service never calls out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class creditService {

    private static final Long RESERVE_ACCOUNT_ID = 1L; // single self-funded reserve for now

    private final CreditLimitRepository creditLimitRepo;
    private final ReserveAccountRepository reserveRepo;
    private final TradeLedgerRepository ledgerRepo;

    /**
     * Must be called from WITHIN the caller's existing transaction
     * (default REQUIRED propagation) so it commits or rolls back atomically
     * with the Order being created. Do not call this from its own
     * transaction boundary.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void reserveConsignmentCapacity(Long merchantId, Long orderId, BigDecimal amount) {
        CreditLimit limit = creditLimitRepo.findByMerchantIdForUpdate(merchantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No credit limit configured for merchant " + merchantId));

        if (limit.getAvailableLimit().compareTo(amount) < 0) {
            throw new CreditLimitExceededException(merchantId, amount, limit.getAvailableLimit());
        }

        ReserveAccount reserve = reserveRepo.findByIdForUpdate(RESERVE_ACCOUNT_ID)
                .orElseThrow(() -> new IllegalStateException("Reserve account not configured"));

        if (reserve.getAvailable().compareTo(amount) < 0) {
            throw new ReserveCapacityExceededException(amount, reserve.getAvailable());
        }

        BigDecimal newBalance = limit.getOutstandingBalance().add(amount);
        limit.setOutstandingBalance(newBalance);
        reserve.setAllocated(reserve.getAllocated().add(amount));

        creditLimitRepo.save(limit);
        reserveRepo.save(reserve);

        ledgerRepo.save(TradeLedgerEntry.builder()
                .merchantId(merchantId)
                .orderId(orderId)
                .entryType(LedgerEntryType.CREDIT_ISSUED)
                .amount(amount)
                .balanceAfter(newBalance)
                .createdBy("SYSTEM")
                .build());

        log.info("Reserved {} consignment capacity for merchant {} (order {}). New outstanding: {}, reserve available: {}",
                amount, merchantId, orderId, newBalance, reserve.getAvailable());
    }

    /**
     * Called by PaymentService after a consignment repayment is confirmed.
     * Same rule applies — call within the caller's existing transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void releaseConsignmentCapacity(Long merchantId, Long orderId, BigDecimal amountPaid, String reference) {
        CreditLimit limit = creditLimitRepo.findByMerchantIdForUpdate(merchantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No credit limit configured for merchant " + merchantId));

        ReserveAccount reserve = reserveRepo.findByIdForUpdate(RESERVE_ACCOUNT_ID)
                .orElseThrow(() -> new IllegalStateException("Reserve account not configured"));

        BigDecimal newBalance = limit.getOutstandingBalance().subtract(amountPaid);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Overpayment detected for merchant {}: outstanding would go negative. Clamping to zero.", merchantId);
            newBalance = BigDecimal.ZERO;
        }

        limit.setOutstandingBalance(newBalance);
        reserve.setAllocated(reserve.getAllocated().subtract(amountPaid).max(BigDecimal.ZERO));

        creditLimitRepo.save(limit);
        reserveRepo.save(reserve);

        ledgerRepo.save(TradeLedgerEntry.builder()
                .merchantId(merchantId)
                .orderId(orderId)
                .entryType(LedgerEntryType.PAYMENT_RECEIVED)
                .amount(amountPaid)
                .balanceAfter(newBalance)
                .reference(reference)
                .createdBy("BILLMANAGER_CALLBACK")
                .build());

        log.info("Released {} consignment capacity for merchant {} (order {}). New outstanding: {}",
                amountPaid, merchantId, orderId, newBalance);
    }



}
