package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockTransferItemRepository extends JpaRepository<StockTransferItem, Long> {

    // You can add custom queries later if needed
}