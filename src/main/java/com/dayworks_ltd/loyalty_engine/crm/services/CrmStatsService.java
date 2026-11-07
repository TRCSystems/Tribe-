package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopQueryRepository;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopStatsRepository;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator.AllTime;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator.LineFact;
import com.dayworks_ltd.loyalty_engine.crm.services.ShopStatsCalculator.OrderFact;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds and serves shop buying profiles, segments and the distributor dashboard. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmStatsService {

    /** Orders older than this are ignored for rhythm and segment calculations. */
    private static final int WINDOW_DAYS = 180;

    private final CrmShopQueryRepository queryRepository;
    private final CrmShopStatsRepository statsRepository;
    private final MerchantRepository merchantRepository;

    @Value("${crm.consignment.min-self-orders:8}")
    private int consignmentMinSelfOrders;

    /** Nightly at 02:30 Kenya time, for every distributor that has orders. */
    @Scheduled(cron = "${crm.stats-cron:0 30 2 * * *}", zone = "${stock-count.zone:Africa/Nairobi}")
    public void refreshAllNightly() {
        for (Long distributorId : queryRepository.distributorIdsWithOrders()) {
            try {
                int n = refresh(distributorId).size();
                log.info("CRM stats refreshed for distributor {}: {} shops", distributorId, n);
            } catch (Exception e) {
                log.error("CRM stats refresh failed for distributor {}: {}", distributorId, e.getMessage(), e);
            }
        }
    }

    @Transactional
    public List<CrmShopStats> refresh(Long distributorId) {
        LocalDateTime now = LocalDateTime.now();
        List<OrderFact> facts = queryRepository.ordersWithItemsSince(distributorId, now.minusDays(WINDOW_DAYS))
                .stream().map(CrmStatsService::toFact).toList();
        Map<Long, AllTime> allTime = new HashMap<>();
        for (Object[] r : queryRepository.firstOrderByShop(distributorId)) {
            allTime.put((Long) r[0], new AllTime((LocalDateTime) r[1], ((Number) r[2]).intValue()));
        }
        Set<Long> linked = merchantRepository.findByDistributor_Id(distributorId).stream()
                .map(Merchant::getId).collect(Collectors.toSet());
        Map<Long, CrmShopStats> previous = statsRepository.findByDistributorId(distributorId).stream()
                .collect(Collectors.toMap(CrmShopStats::getShopMerchantId, Function.identity(), (a, b) -> a));

        List<CrmShopStats> stats = ShopStatsCalculator.compute(distributorId, linked, facts, allTime, previous,
                consignmentMinSelfOrders, now);
        return statsRepository.saveAll(stats);
    }

    /** Current stats per shop, computed first if missing or older than a day. */
    @Transactional
    public Map<Long, CrmShopStats> statsByShop(Long distributorId) {
        List<CrmShopStats> stats = statsRepository.findByDistributorId(distributorId);
        boolean stale = stats.isEmpty() || stats.stream()
                .anyMatch(s -> s.getComputedAt().isBefore(LocalDateTime.now().minusHours(26)));
        if (stale) stats = refresh(distributorId);
        return stats.stream().collect(Collectors.toMap(CrmShopStats::getShopMerchantId, Function.identity(), (a, b) -> a));
    }

    /**
     * Headline numbers for the distributor: last 30 days vs the 30 days before.
     * These are the success measures: order count, basket size, frequency and self-ordering.
     */
    @Transactional
    public Map<String, Object> dashboard(Long distributorId) {
        LocalDateTime now = LocalDateTime.now();
        List<OrderFact> facts = queryRepository.ordersWithItemsSince(distributorId, now.minusDays(60))
                .stream().map(CrmStatsService::toFact).filter(o -> !o.cancelled()).toList();
        List<OrderFact> cur = facts.stream().filter(o -> o.date().isAfter(now.minusDays(30))).toList();
        List<OrderFact> prev = facts.stream().filter(o -> !o.date().isAfter(now.minusDays(30))).toList();

        Map<Long, CrmShopStats> stats = statsByShop(distributorId);
        Map<String, Long> segments = new LinkedHashMap<>();
        for (CrmShopStats.Segment s : CrmShopStats.Segment.values()) segments.put(s.name(), 0L);
        stats.values().forEach(s -> segments.merge(s.getSegment().name(), 1L, Long::sum));

        List<Double> gaps = stats.values().stream()
                .filter(s -> Boolean.FALSE.equals(s.getGapIsEstimated()) && s.getUsualGapDays() != null)
                .map(CrmShopStats::getUsualGapDays).toList();

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("period", "Last 30 days vs previous 30 days");
        d.put("current", periodView(cur));
        d.put("previous", periodView(prev));
        d.put("medianDaysBetweenOrders", gaps.isEmpty() ? null : ShopStatsCalculator.median(new ArrayList<>(gaps)));
        d.put("segments", segments);
        d.put("shops", stats.size());
        d.put("consignmentReady", stats.values().stream().filter(s -> Boolean.TRUE.equals(s.getConsignmentReady())).count());
        d.put("computedAt", stats.values().stream().map(CrmShopStats::getComputedAt).max(Comparator.naturalOrder()).orElse(null));
        return d;
    }

    // ───────────────────────────── internals ─────────────────────────────

    private static Map<String, Object> periodView(List<OrderFact> orders) {
        long shops = orders.stream().map(OrderFact::shopId).distinct().count();
        BigDecimal revenue = orders.stream().map(o -> o.total() == null ? BigDecimal.ZERO : o.total())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        long self = orders.stream().filter(OrderFact::selfPlaced).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orders", orders.size());
        m.put("activeShops", shops);
        m.put("ordersPerActiveShop", shops == 0 ? null : Math.round(10.0 * orders.size() / shops) / 10.0);
        m.put("revenueKes", revenue);
        m.put("avgBasketKes", orders.isEmpty() ? null
                : revenue.divide(BigDecimal.valueOf(orders.size()), 2, RoundingMode.HALF_UP));
        m.put("avgLinesPerOrder", orders.isEmpty() ? null
                : Math.round(10.0 * orders.stream().mapToInt(o -> o.lines().size()).average().orElse(0)) / 10.0);
        m.put("selfOrderSharePct", orders.isEmpty() ? null : Math.round(1000.0 * self / orders.size()) / 10.0);
        return m;
    }

    private static OrderFact toFact(Order o) {
        List<LineFact> lines = o.getItems() == null ? List.of() : o.getItems().stream()
                .map(i -> new LineFact(i.getItemCode(), i.getItemName(), i.getQuantity() == null ? 0 : i.getQuantity()))
                .toList();
        return new OrderFact(
                o.getMerchant().getId(),
                o.getOrderDate(),
                o.getTotalAmount(),
                o.getStatus() == OrderStatus.CANCELLED,
                o.getOrderSource() == OrderSource.SHOP,
                lines);
    }
}
