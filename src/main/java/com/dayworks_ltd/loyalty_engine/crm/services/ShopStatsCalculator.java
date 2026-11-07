package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats.Segment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure calculation of shop buying profiles and segments from order facts (no Spring, no database).
 *
 * Segment rules, in priority order:
 *   PROSPECT  never ordered
 *   NEW       first order within the last 30 days
 *   LOST      days since last order > 3 x usual gap, and at least 21 days
 *   AT_RISK   days since last order > 1.5 x usual gap, and at least 7 days
 *   CHAMPION  12+ orders in 90 days and average order at or above the distributor's median
 *   DUE       days since last order >= 0.8 x usual gap
 *   ACTIVE    everything else
 */
public final class ShopStatsCalculator {

    private ShopStatsCalculator() {}

    public record LineFact(String itemCode, String itemName, int quantity) {}

    public record OrderFact(Long shopId, LocalDateTime date, BigDecimal total, boolean cancelled,
                            boolean selfPlaced, List<LineFact> lines) {}

    /** Shop's all-time first order and count (non-cancelled), which may predate the analysis window. */
    public record AllTime(LocalDateTime firstOrder, int orders) {}

    static final double DEFAULT_GAP_DAYS = 7.0;
    static final int CHAMPION_MIN_ORDERS_90D = 12;
    static final double CROSS_SELL_MIN_SHOP_SHARE = 0.30;

    public static List<CrmShopStats> compute(Long distributorId,
                                             Collection<Long> shopIds,
                                             List<OrderFact> orders,
                                             Map<Long, AllTime> allTime,
                                             Map<Long, CrmShopStats> previous,
                                             int consignmentMinSelfOrders,
                                             LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        Map<Long, List<OrderFact>> byShop = orders.stream().collect(Collectors.groupingBy(OrderFact::shopId));
        List<OrderFact> valid = orders.stream().filter(o -> !o.cancelled()).toList();

        // Distributor-wide reference values
        Map<Long, Double> medianGaps = new HashMap<>();
        for (Map.Entry<Long, List<OrderFact>> e : byShop.entrySet()) {
            Double g = medianGap(e.getValue().stream().filter(o -> !o.cancelled()).map(OrderFact::date).toList());
            if (g != null) medianGaps.put(e.getKey(), g);
        }
        double defaultGap = medianGaps.isEmpty() ? DEFAULT_GAP_DAYS : median(new ArrayList<>(medianGaps.values()));
        BigDecimal medianBasket = medianBasket(valid.stream()
                .filter(o -> o.date().isAfter(now.minusDays(90))).toList());
        Map<String, Double> itemShopShare = itemShopShare(valid.stream()
                .filter(o -> o.date().isAfter(now.minusDays(90))).toList());
        Map<String, String> itemNames = new HashMap<>();
        valid.forEach(o -> o.lines().forEach(l -> itemNames.putIfAbsent(l.itemCode(), l.itemName())));

        List<CrmShopStats> result = new ArrayList<>();
        Set<Long> all = new LinkedHashSet<>(shopIds);
        all.addAll(byShop.keySet());
        all.addAll(allTime.keySet());
        all.remove(distributorId);

        for (Long shopId : all) {
            List<OrderFact> shopOrders = byShop.getOrDefault(shopId, List.of());
            List<OrderFact> ok = shopOrders.stream().filter(o -> !o.cancelled())
                    .sorted(Comparator.comparing(OrderFact::date)).toList();
            AllTime at = allTime.get(shopId);

            CrmShopStats s = CrmShopStats.builder()
                    .distributorId(distributorId)
                    .shopMerchantId(shopId)
                    .computedAt(now)
                    .build();

            if (ok.isEmpty() && at == null) {
                s.setSegment(Segment.PROSPECT);
                s.setOrdersAllTime(0);
                s.setOrders30d(0);
                s.setOrders90d(0);
                s.setRevenue30d(BigDecimal.ZERO);
                s.setRevenue90d(BigDecimal.ZERO);
                s.setConsignmentReady(false);
                s.setSelfOrders60d(0);
                s.setCancellations60d(0);
                s.setTopItemsJson("[]");
                s.setCrossSellJson("[]");
                carrySegmentHistory(s, previous.get(shopId), now);
                result.add(s);
                continue;
            }

            LocalDateTime last = ok.isEmpty() ? null : ok.get(ok.size() - 1).date();
            LocalDateTime first = at != null ? at.firstOrder() : ok.get(0).date();
            int daysSince = last == null ? Integer.MAX_VALUE : (int) ChronoUnit.DAYS.between(last.toLocalDate(), today);

            Double gap = medianGaps.get(shopId);
            boolean estimated = gap == null;
            double usualGap = estimated ? defaultGap : gap;

            List<OrderFact> last30 = window(ok, now.minusDays(30), now);
            List<OrderFact> prev30 = window(ok, now.minusDays(60), now.minusDays(30));
            List<OrderFact> last90 = window(ok, now.minusDays(90), now);
            List<OrderFact> last60All = window(shopOrders, now.minusDays(60), now);

            BigDecimal avgBasket = avg(last90);
            int selfOrders = (int) last60All.stream().filter(o -> !o.cancelled() && o.selfPlaced()).count();
            int validLast60 = (int) last60All.stream().filter(o -> !o.cancelled()).count();
            int cancels = (int) last60All.stream().filter(OrderFact::cancelled).count();

            s.setFirstOrderAt(first);
            s.setLastOrderAt(last);
            s.setDaysSinceLastOrder(last == null ? null : daysSince);
            s.setUsualGapDays(round1(usualGap));
            s.setGapIsEstimated(estimated);
            s.setNextExpectedOrderDate(last == null ? null : last.toLocalDate().plusDays(Math.round(usualGap)));
            s.setOrdersAllTime(at != null ? at.orders() : ok.size());
            s.setOrders30d(last30.size());
            s.setOrders90d(last90.size());
            s.setRevenue30d(sum(last30));
            s.setRevenue90d(sum(last90));
            s.setAvgBasketKes(avgBasket);
            s.setAvgLinesPerOrder(last90.isEmpty() ? null
                    : round1(last90.stream().mapToInt(o -> o.lines().size()).average().orElse(0)));
            s.setBasketTrendPct(trendPct(avg(last30), avg(prev30)));
            s.setSelfOrders60d(selfOrders);
            s.setSelfOrderShare60d(validLast60 == 0 ? null : round1(100.0 * selfOrders / validLast60));
            s.setCancellations60d(cancels);
            s.setConsignmentReady(selfOrders >= consignmentMinSelfOrders && cancels == 0);
            s.setTopItemsJson(topItemsJson(last90));
            s.setCrossSellJson(crossSellJson(last90, itemShopShare, itemNames));

            Segment seg;
            if (first != null && ChronoUnit.DAYS.between(first.toLocalDate(), today) <= 30) {
                seg = Segment.NEW;
            } else if (last == null || (daysSince > 3 * usualGap && daysSince >= 21)) {
                seg = Segment.LOST;
            } else if (daysSince > 1.5 * usualGap && daysSince >= 7) {
                seg = Segment.AT_RISK;
            } else if (last90.size() >= CHAMPION_MIN_ORDERS_90D && avgBasket != null && medianBasket != null
                    && avgBasket.compareTo(medianBasket) >= 0) {
                seg = Segment.CHAMPION;
            } else if (daysSince >= 0.8 * usualGap) {
                seg = Segment.DUE;
            } else {
                seg = Segment.ACTIVE;
            }
            s.setSegment(seg);
            carrySegmentHistory(s, previous.get(shopId), now);
            result.add(s);
        }
        return result;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Median days between distinct order days; null with fewer than two gaps. */
    static Double medianGap(List<LocalDateTime> dates) {
        List<LocalDate> days = dates.stream().map(LocalDateTime::toLocalDate).distinct().sorted().toList();
        if (days.size() < 3) return null;
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            gaps.add((double) ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
        }
        return median(gaps);
    }

    static double median(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    private static BigDecimal medianBasket(List<OrderFact> orders) {
        if (orders.isEmpty()) return null;
        List<Double> totals = orders.stream().map(o -> o.total() == null ? 0.0 : o.total().doubleValue()).toList();
        return BigDecimal.valueOf(median(new ArrayList<>(totals))).setScale(2, RoundingMode.HALF_UP);
    }

    /** Share of the distributor's active shops that bought each item. */
    private static Map<String, Double> itemShopShare(List<OrderFact> orders) {
        Map<String, Set<Long>> buyers = new HashMap<>();
        Set<Long> shops = new HashSet<>();
        for (OrderFact o : orders) {
            shops.add(o.shopId());
            o.lines().forEach(l -> buyers.computeIfAbsent(l.itemCode(), k -> new HashSet<>()).add(o.shopId()));
        }
        Map<String, Double> share = new HashMap<>();
        buyers.forEach((code, b) -> share.put(code, shops.isEmpty() ? 0 : (double) b.size() / shops.size()));
        return share;
    }

    private static String topItemsJson(List<OrderFact> orders) {
        Map<String, int[]> agg = new LinkedHashMap<>(); // code -> [units, orders]
        Map<String, String> names = new HashMap<>();
        for (OrderFact o : orders) {
            Set<String> seen = new HashSet<>();
            for (LineFact l : o.lines()) {
                int[] a = agg.computeIfAbsent(l.itemCode(), k -> new int[2]);
                a[0] += l.quantity();
                if (seen.add(l.itemCode())) a[1]++;
                names.putIfAbsent(l.itemCode(), l.itemName());
            }
        }
        return agg.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]))
                .limit(5)
                .map(e -> "{\"itemCode\":" + json(e.getKey()) + ",\"itemName\":" + json(names.get(e.getKey()))
                        + ",\"units\":" + e.getValue()[0] + ",\"orders\":" + e.getValue()[1] + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String crossSellJson(List<OrderFact> shopOrders90, Map<String, Double> itemShopShare,
                                        Map<String, String> names) {
        if (shopOrders90.isEmpty()) return "[]";
        Set<String> bought = shopOrders90.stream().flatMap(o -> o.lines().stream())
                .map(LineFact::itemCode).collect(Collectors.toSet());
        return itemShopShare.entrySet().stream()
                .filter(e -> e.getValue() >= CROSS_SELL_MIN_SHOP_SHARE && !bought.contains(e.getKey()))
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(3)
                .map(e -> "{\"itemCode\":" + json(e.getKey()) + ",\"itemName\":" + json(names.get(e.getKey()))
                        + ",\"shopShare\":" + Math.round(e.getValue() * 100) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static void carrySegmentHistory(CrmShopStats s, CrmShopStats prev, LocalDateTime now) {
        if (prev == null) {
            s.setSegmentChangedAt(now);
            return;
        }
        s.setId(prev.getId());
        if (prev.getSegment() != s.getSegment()) {
            s.setPreviousSegment(prev.getSegment());
            s.setSegmentChangedAt(now);
        } else {
            s.setPreviousSegment(prev.getPreviousSegment());
            s.setSegmentChangedAt(prev.getSegmentChangedAt());
        }
    }

    private static List<OrderFact> window(List<OrderFact> orders, LocalDateTime from, LocalDateTime to) {
        return orders.stream().filter(o -> !o.date().isBefore(from) && !o.date().isAfter(to)).toList();
    }

    private static BigDecimal sum(List<OrderFact> orders) {
        return orders.stream().map(o -> o.total() == null ? BigDecimal.ZERO : o.total())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(List<OrderFact> orders) {
        if (orders.isEmpty()) return null;
        return sum(orders).divide(BigDecimal.valueOf(orders.size()), 2, RoundingMode.HALF_UP);
    }

    static Double trendPct(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) return null;
        return round1(current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 4, RoundingMode.HALF_UP).doubleValue());
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String json(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
