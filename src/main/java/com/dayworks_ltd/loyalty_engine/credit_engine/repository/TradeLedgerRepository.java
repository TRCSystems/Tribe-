package com.dayworks_ltd.loyalty_engine.credit_engine.repository;



import com.dayworks_ltd.loyalty_engine.credit_engine.model.TradeLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeLedgerRepository extends JpaRepository<TradeLedgerEntry, Long> {

    List<TradeLedgerEntry> findByMerchantIdOrderByCreatedAtAsc(Long merchantId);

    // No update/delete methods exposed on purpose — append-only.
}
