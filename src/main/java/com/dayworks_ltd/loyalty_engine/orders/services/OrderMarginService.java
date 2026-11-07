package com.dayworks_ltd.loyalty_engine.orders.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMarginService {

    private final InventoryRepository inventoryStockRepository;

    /**
     * Computes a PROJECTED margin for an order at creation time, using the
     * merchant's own current retail price per item (inventory_stock.unit_price).
     * This is an estimate for credit-affordability screening only — never a
     * realized margin, and never backfilled into actual-sales tables.
     * Mutates the order and its items in place; caller persists.
     */
    public void applyProjectedMargin(Order order, String merchantId) {
        log.info("calling the margin calculator");

        if (merchantId == null || merchantId.isBlank()) {
            log.warn("No merchantId supplied from token – cannot resolve retail prices");
            order.setProjectedMargin(BigDecimal.ZERO);
            order.setMarginDataComplete(false);
            order.setMarginCoverageRatio(null);
            return;
        }

        log.info("Using merchantId from token: {}", merchantId);

        BigDecimal totalMargin = BigDecimal.ZERO;
        boolean allResolved = true;

        for (OrderItem item : order.getItems()) {
            BigDecimal retailPrice = resolveRetailPrice(merchantId, item.getItemCode());

            log.info("Retail price resolved for itemCode={}, merchantId={}: {}",
                    item.getItemCode(), merchantId, retailPrice);
            item.setRetailUnitPrice(retailPrice);

            if (retailPrice == null || item.getQuantity() == null) {
                allResolved = false;
                item.setItemMargin(null);
                continue;
            }

            BigDecimal wholesale = item.getWholesalePrice() != null
                    ? item.getWholesalePrice()
                    : BigDecimal.ZERO;

            BigDecimal perUnitMargin = retailPrice.subtract(wholesale);
            BigDecimal lineMargin = perUnitMargin.multiply(BigDecimal.valueOf(item.getQuantity()));
            item.setItemMargin(lineMargin);
            totalMargin = totalMargin.add(lineMargin);
        }

        log.info("doing database writes");

        order.setProjectedMargin(totalMargin);
        order.setMarginDataComplete(allResolved);

        BigDecimal financed = order.getFinancedAmount();
        if (financed != null && financed.compareTo(BigDecimal.ZERO) > 0) {
            order.setMarginCoverageRatio(
                    totalMargin.divide(financed, 4, RoundingMode.HALF_UP));
        } else {
            order.setMarginCoverageRatio(null);
        }
    }
    private BigDecimal resolveRetailPrice(String merchantId, String itemCode) {
        Optional<Inventory> stock = inventoryStockRepository
                .findTopByMerchantIdAndItemCodeOrderByRecordDateDesc(merchantId, itemCode);

        if (stock.isPresent()
                && stock.get().getUnitPrice() != null
                && stock.get().getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            return stock.get().getUnitPrice();
        }

        // No reliable signal — do not fabricate one.
        return null;
    }
}