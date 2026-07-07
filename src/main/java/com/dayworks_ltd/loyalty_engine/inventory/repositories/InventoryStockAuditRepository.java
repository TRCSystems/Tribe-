package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.InventoryStockAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryStockAuditRepository extends JpaRepository<InventoryStockAudit, Long> {
}