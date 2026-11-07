package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.PriceChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceChangeRepository extends JpaRepository<PriceChange, Long> {

    List<PriceChange> findByMerchantIdAndInventoryIdOrderByChangedAtDescIdDesc(String merchantId, Long inventoryId, Pageable page);

    List<PriceChange> findByMerchantIdOrderByChangedAtDescIdDesc(String merchantId, Pageable page);

    List<PriceChange> findByMerchantIdAndItemCodeAndChangedAtBetweenOrderByChangedAtAscIdAsc(
            String merchantId, String itemCode, java.time.LocalDateTime from, java.time.LocalDateTime to);
}
