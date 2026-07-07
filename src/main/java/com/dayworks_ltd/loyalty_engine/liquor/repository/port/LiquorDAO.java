package com.dayworks_ltd.loyalty_engine.liquor.repository.port;

import com.dayworks_ltd.loyalty_engine.credit_engine.model.CanonicalLiquorProduct;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;

import java.util.List;

public interface LiquorDAO {
    List<Inventory> getInventoryStockMatchingName(Long merchantId, String itemNameWhereClause);
}
