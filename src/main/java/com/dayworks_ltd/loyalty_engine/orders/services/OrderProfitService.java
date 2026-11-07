package com.dayworks_ltd.loyalty_engine.orders.services;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * How much the distributor made on each wholesale order:
 *     margin = sum(quantity x (wholesale price charged - unit cost))
 * The wholesale price is the one on the order line (from the wholesale price config at order time);
 * the unit cost is the distributor's own cost in inventory.
 *
 * If any line has no unit cost, the margin is left empty (marginComplete = false) rather than overstated.
 * This is the distributor's own margin — never return it to the shop.
 */
@Service
@RequiredArgsConstructor
public class OrderProfitService {

    public record LineProfit(BigDecimal unitCost, BigDecimal lineMargin) {}

    public record Profit(BigDecimal revenueKes, BigDecimal costKes, BigDecimal marginKes,
                         BigDecimal marginPct, boolean marginComplete, Map<Long, LineProfit> lines) {}

    private final InventoryRepository inventoryRepository;

    /** Margin for each order, keyed by order id. */
    public Map<Long, Profit> profitFor(String distributorMerchantId, List<Order> orders) {
        if (orders.isEmpty()) return Map.of();
        Map<String, BigDecimal> unitCost = currentUnitCosts(distributorMerchantId);
        Map<Long, Profit> result = new HashMap<>();
        for (Order o : orders) result.put(o.getId(), profit(o, unitCost));
        return result;
    }

    static Profit profit(Order o, Map<String, BigDecimal> unitCost) {
        BigDecimal revenue = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        boolean complete = true;
        Map<Long, LineProfit> lines = new LinkedHashMap<>();
        for (OrderItem i : o.getItems() == null ? List.<OrderItem>of() : o.getItems()) {
            int qty = i.getQuantity() == null ? 0 : i.getQuantity();
            BigDecimal price = i.getWholesalePrice() == null ? BigDecimal.ZERO : i.getWholesalePrice();
            BigDecimal lineTotal = i.getLineTotal() != null ? i.getLineTotal() : price.multiply(BigDecimal.valueOf(qty));
            revenue = revenue.add(lineTotal);

            BigDecimal c = unitCost.get(i.getItemCode());
            if (c == null || c.signum() <= 0) {
                complete = false;
                lines.put(i.getId(), new LineProfit(null, null));
            } else {
                BigDecimal lineCost = c.multiply(BigDecimal.valueOf(qty));
                cost = cost.add(lineCost);
                lines.put(i.getId(), new LineProfit(c, lineTotal.subtract(lineCost).setScale(2, RoundingMode.HALF_UP)));
            }
        }
        revenue = revenue.setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = complete ? revenue.subtract(cost).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal pct = margin == null || revenue.signum() == 0 ? null
                : margin.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
        return new Profit(revenue, complete ? cost.setScale(2, RoundingMode.HALF_UP) : null, margin, pct, complete, lines);
    }

    /** The distributor's current unit cost per item code (latest inventory row wins). */
    private Map<String, BigDecimal> currentUnitCosts(String distributorMerchantId) {
        Map<String, Inventory> latest = new HashMap<>();
        for (Inventory i : inventoryRepository.findByMerchantId(distributorMerchantId)) {
            latest.merge(i.getItemCode(), i, (a, b) -> {
                LocalDate da = a.getRecordDate(), db = b.getRecordDate();
                if (da != null && db != null && !da.equals(db)) return db.isAfter(da) ? b : a;
                return b.getId() != null && a.getId() != null && b.getId() > a.getId() ? b : a;
            });
        }
        Map<String, BigDecimal> costs = new HashMap<>();
        latest.forEach((code, inv) -> costs.put(code, inv.getUnitCost()));
        return costs;
    }
}
