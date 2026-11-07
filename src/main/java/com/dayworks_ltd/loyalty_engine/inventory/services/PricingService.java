package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.PriceChange;
import com.dayworks_ltd.loyalty_engine.inventory.models.WholesalePriceConfig;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.PriceChangeRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.WholesalePriceConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Unit cost, selling (retail) price and wholesale price per item, in one place. Every change is
 * logged with who made it. A new wholesale price closes the previous one rather than overwriting
 * it, so past orders keep their history.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    private final InventoryRepository inventoryRepository;
    private final WholesalePriceConfigRepository wholesaleRepository;
    private final PriceChangeRepository changeRepository;

    /**
     * One item's requested changes. Null fields are left as they are; clearWholesalePrice removes
     * the wholesale price (the item drops out of the shops' order catalogue).
     */
    public record PriceUpdate(Long id, BigDecimal unitCost, BigDecimal unitPrice, BigDecimal wholesalePrice,
                              Boolean clearWholesalePrice) {}

    /** Every item with its three prices and margins. */
    public List<Map<String, Object>> list(String merchantId) {
        Map<String, WholesalePriceConfig> wholesale = currentWholesale(merchantId);
        return inventoryRepository.findByMerchantId(merchantId).stream()
                .sorted(Comparator.comparing(i -> i.getItemName() == null ? "" : i.getItemName().toLowerCase()))
                .map(i -> row(i, wholesale.get(i.getItemCode())))
                .toList();
    }

    /** Applies all updates or none. Returns the updated rows. */
    @Transactional
    public List<Map<String, Object>> update(String merchantId, Long userId, List<PriceUpdate> updates) {
        if (updates == null || updates.isEmpty()) throw new IllegalArgumentException("Nothing to update");
        if (updates.size() > 500) throw new IllegalArgumentException("Update at most 500 items at a time");

        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceUpdate u : updates) {
            if (u == null || u.id() == null) throw new IllegalArgumentException("Each update needs an item id");
            Inventory item = inventoryRepository.findByIdAndMerchantId(u.id(), merchantId)
                    .orElseThrow(() -> new IllegalArgumentException("Item " + u.id() + " not found"));
            String name = item.getItemName();

            if (u.unitCost() != null) {
                BigDecimal cost = money(u.unitCost(), name + ": unit cost", false);
                if (!same(cost, item.getUnitCost())) {
                    log(item, PriceChange.Field.UNIT_COST, item.getUnitCost(), cost, userId);
                    item.setUnitCost(cost);
                }
            }
            if (u.unitPrice() != null) {
                BigDecimal price = money(u.unitPrice(), name + ": selling price", false);
                if (!same(price, item.getUnitPrice())) {
                    log(item, PriceChange.Field.UNIT_PRICE, item.getUnitPrice(), price, userId);
                    item.setUnitPrice(price);
                    item.computeTotalSales();
                }
            }
            inventoryRepository.save(item);

            if (Boolean.TRUE.equals(u.clearWholesalePrice())) {
                clearWholesale(item, userId);
            } else if (u.wholesalePrice() != null) {
                setWholesale(item, money(u.wholesalePrice(), name + ": wholesale price", true), userId);
            }
            out.add(row(item, wholesaleRepository.findLatestPrice(item.getItemCode(), merchantId).orElse(null)));
        }
        return out;
    }

    /** What changed on one item, newest first. */
    public List<Map<String, Object>> history(String merchantId, Long inventoryId) {
        inventoryRepository.findByIdAndMerchantId(inventoryId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        return changeRepository.findByMerchantIdAndInventoryIdOrderByChangedAtDescIdDesc(
                        merchantId, inventoryId, PageRequest.of(0, 100)).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("field", c.getField());
                    m.put("oldValue", c.getOldValue());
                    m.put("newValue", c.getNewValue());
                    m.put("changedByUserId", c.getChangedByUserId());
                    m.put("changedAt", c.getChangedAt());
                    return m;
                })
                .toList();
    }

    // ───────────────────────────── wholesale ─────────────────────────────

    /** Sets the wholesale price from today. The previous price is closed (kept for history). */
    @Transactional
    public void setWholesale(Inventory item, BigDecimal price, Long userId) {
        List<WholesalePriceConfig> active = activeRows(item);
        BigDecimal current = active.stream().max(Comparator.comparing(WholesalePriceConfig::getEffectiveFrom)
                        .thenComparing(WholesalePriceConfig::getId))
                .map(WholesalePriceConfig::getWholesalePrice).orElse(null);
        if (same(price, current)) return;

        close(active);
        LocalDateTime now = LocalDateTime.now();
        WholesalePriceConfig row = new WholesalePriceConfig();
        row.setItemCode(item.getItemCode());
        row.setMerchantId(item.getMerchantId());
        row.setWholesalePrice(price);
        row.setEffectiveFrom(LocalDate.now());
        row.setIsActive(true);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        wholesaleRepository.save(row);
        log(item, PriceChange.Field.WHOLESALE_PRICE, current, price, userId);
    }

    /** Removes the wholesale price: the item is no longer offered to shops for ordering. */
    @Transactional
    public void clearWholesale(Inventory item, Long userId) {
        List<WholesalePriceConfig> active = activeRows(item);
        if (active.isEmpty()) return;
        BigDecimal current = active.stream().max(Comparator.comparing(WholesalePriceConfig::getEffectiveFrom)
                        .thenComparing(WholesalePriceConfig::getId))
                .map(WholesalePriceConfig::getWholesalePrice).orElse(null);
        close(active);
        log(item, PriceChange.Field.WHOLESALE_PRICE, current, null, userId);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private List<WholesalePriceConfig> activeRows(Inventory item) {
        if (item.getItemCode() == null) throw new IllegalArgumentException(item.getItemName() + " has no item code");
        return wholesaleRepository.findByItemCodeAndMerchantIdAndIsActiveTrue(item.getItemCode(), item.getMerchantId());
    }

    private void close(List<WholesalePriceConfig> rows) {
        LocalDate today = LocalDate.now();
        for (WholesalePriceConfig r : rows) {
            r.setIsActive(false);
            // effectiveTo never earlier than effectiveFrom
            r.setEffectiveTo(r.getEffectiveFrom() != null && r.getEffectiveFrom().isAfter(today) ? r.getEffectiveFrom() : today);
            r.setUpdatedAt(LocalDateTime.now());
        }
        wholesaleRepository.saveAll(rows);
    }

    /** Latest active wholesale row per item code (the list is newest first, so the first one wins). */
    private Map<String, WholesalePriceConfig> currentWholesale(String merchantId) {
        Map<String, WholesalePriceConfig> m = new HashMap<>();
        for (WholesalePriceConfig w : wholesaleRepository.findActiveByMerchant(merchantId)) {
            m.putIfAbsent(w.getItemCode(), w);
        }
        return m;
    }

    static Map<String, Object> row(Inventory i, WholesalePriceConfig w) {
        BigDecimal cost = i.getUnitCost();
        BigDecimal retail = i.getUnitPrice();
        BigDecimal wholesale = w == null ? null : w.getWholesalePrice();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("itemCode", i.getItemCode());
        m.put("itemName", i.getItemName());
        m.put("category", i.getProductCategory());
        m.put("unitCost", cost);
        m.put("unitPrice", retail);
        m.put("wholesalePrice", wholesale);
        m.put("wholesaleSince", w == null ? null : w.getEffectiveFrom());
        m.put("retailMarginPct", marginPct(retail, cost));
        m.put("wholesaleMarginPct", marginPct(wholesale, cost));
        return m;
    }

    /** (price − cost) / price × 100, or null when either is unknown. */
    static BigDecimal marginPct(BigDecimal price, BigDecimal cost) {
        if (price == null || cost == null || price.signum() <= 0 || cost.signum() <= 0) return null;
        return price.subtract(cost).multiply(BigDecimal.valueOf(100)).divide(price, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v, String label, boolean mustBePositive) {
        if (v.signum() < 0) throw new IllegalArgumentException(label + " can't be negative");
        if (mustBePositive && v.signum() == 0) {
            throw new IllegalArgumentException(label + " must be more than 0 (or remove the wholesale price)");
        }
        if (v.compareTo(MAX_PRICE) >= 0) throw new IllegalArgumentException(label + " is too large");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void log(Inventory item, PriceChange.Field field, BigDecimal oldValue, BigDecimal newValue, Long userId) {
        changeRepository.save(PriceChange.builder()
                .merchantId(item.getMerchantId())
                .inventoryId(item.getId())
                .itemCode(item.getItemCode())
                .field(field)
                .oldValue(oldValue)
                .newValue(newValue)
                .changedByUserId(userId)
                .build());
    }
}
