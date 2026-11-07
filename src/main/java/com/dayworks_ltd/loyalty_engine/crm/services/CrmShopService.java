package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.auth.enums.Status;
import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmShopStats;
import com.dayworks_ltd.loyalty_engine.crm.models.RepAssignment;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmContactRepository;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmShopQueryRepository;
import com.dayworks_ltd.loyalty_engine.crm.repositories.RepAssignmentRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A distributor's shops, its field reps, and which rep looks after which shop.
 * "Shops" = merchants linked to the distributor plus any merchant that has ordered from it.
 */
@Service
@RequiredArgsConstructor
public class CrmShopService {

    private final MerchantRepository merchantRepository;
    private final CrmShopQueryRepository shopQueryRepository;
    private final RepAssignmentRepository assignmentRepository;
    private final CrmContactRepository contactRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CrmStatsService statsService;

    // ───────────────────────────── shops ─────────────────────────────

    /** Shop list for the CRM. A rep sees only the shops assigned to them. */
    public List<Map<String, Object>> listShops(Long distributorId, User viewer) {
        Map<Long, Object[]> orders = shopQueryRepository.orderSummaryByShop(distributorId).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> r));
        Map<Long, RepAssignment> assignments = assignmentRepository.findByDistributorId(distributorId).stream()
                .collect(Collectors.toMap(RepAssignment::getShopMerchantId, Function.identity()));
        Map<Long, CrmContact> contacts = contactRepository.findByDistributorId(distributorId).stream()
                .collect(Collectors.toMap(CrmContact::getShopMerchantId, Function.identity()));
        Map<Long, String> repNames = repsOf(distributorId).stream()
                .collect(Collectors.toMap(User::getUserId, User::getUsername));
        Map<Long, CrmShopStats> stats = statsService.statsByShop(distributorId);

        Set<Long> shopIds = new LinkedHashSet<>(orders.keySet());
        merchantRepository.findByDistributor_Id(distributorId).forEach(m -> shopIds.add(m.getId()));
        shopIds.remove(distributorId);

        boolean repView = viewer.getRole() == UserRole.FIELD_REP;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Merchant shop : merchantRepository.findAllById(shopIds)) {
            RepAssignment a = assignments.get(shop.getId());
            if (repView && (a == null || !a.getRepUserId().equals(viewer.getUserId()))) continue;

            Object[] o = orders.get(shop.getId());
            CrmContact c = contacts.get(shop.getId());
            LocalDateTime lastOrder = o == null ? null : (LocalDateTime) o[2];

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shopId", shop.getId());
            m.put("name", shop.getBusinessName());
            m.put("location", shop.getLocation());
            m.put("phone", c != null && c.getPhone() != null ? c.getPhone() : shop.getBusinessPhone());
            m.put("orderCount", o == null ? 0 : ((Number) o[1]).intValue());
            m.put("lastOrderDate", lastOrder);
            m.put("daysSinceLastOrder", lastOrder == null ? null : ChronoUnit.DAYS.between(lastOrder.toLocalDate(), LocalDateTime.now().toLocalDate()));
            m.put("totalOrderedKes", o == null ? BigDecimal.ZERO : o[3]);
            m.put("repUserId", a == null ? null : a.getRepUserId());
            m.put("repName", a == null ? null : repNames.get(a.getRepUserId()));
            m.put("preferredChannel", c == null ? "SMS" : c.getPreferredChannel());
            m.put("marketingOptOut", c != null && Boolean.TRUE.equals(c.getMarketingOptOut()));
            CrmShopStats st = stats.get(shop.getId());
            m.put("segment", st == null ? (o == null ? "PROSPECT" : null) : st.getSegment());
            m.put("previousSegment", st == null ? null : st.getPreviousSegment());
            m.put("usualGapDays", st == null ? null : st.getUsualGapDays());
            m.put("nextExpectedOrderDate", st == null ? null : st.getNextExpectedOrderDate());
            m.put("orders30d", st == null ? 0 : st.getOrders30d());
            m.put("avgBasketKes", st == null ? null : st.getAvgBasketKes());
            m.put("basketTrendPct", st == null ? null : st.getBasketTrendPct());
            m.put("selfOrderShare60d", st == null ? null : st.getSelfOrderShare60d());
            m.put("consignmentReady", st != null && Boolean.TRUE.equals(st.getConsignmentReady()));
            result.add(m);
        }
        result.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("orderCount")).reversed());
        return result;
    }

    /** Everything about one shop: profile, buying stats, top and cross-sell products, recent orders. */
    public Map<String, Object> shopProfile(Long distributorId, Long shopId, User viewer) {
        Map<String, Object> summary = listShops(distributorId, viewer).stream()
                .filter(s -> shopId.equals(s.get("shopId"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Shop not found"));
        CrmShopStats st = statsService.statsByShop(distributorId).get(shopId);

        Map<String, Object> p = new LinkedHashMap<>(summary);
        if (st != null) {
            p.put("firstOrderAt", st.getFirstOrderAt());
            p.put("ordersAllTime", st.getOrdersAllTime());
            p.put("orders90d", st.getOrders90d());
            p.put("revenue30d", st.getRevenue30d());
            p.put("revenue90d", st.getRevenue90d());
            p.put("avgLinesPerOrder", st.getAvgLinesPerOrder());
            p.put("gapIsEstimated", st.getGapIsEstimated());
            p.put("selfOrders60d", st.getSelfOrders60d());
            p.put("cancellations60d", st.getCancellations60d());
            p.put("segmentChangedAt", st.getSegmentChangedAt());
            p.put("topItems", parseJsonList(st.getTopItemsJson()));
            p.put("crossSell", parseJsonList(st.getCrossSellJson()));
            p.put("statsComputedAt", st.getComputedAt());
        }

        List<Map<String, Object>> recent = shopQueryRepository
                .shopOrdersWithItemsSince(distributorId, shopId, LocalDateTime.now().minusDays(180)).stream()
                .sorted(Comparator.comparing(com.dayworks_ltd.loyalty_engine.orders.models.Order::getOrderDate).reversed())
                .limit(15)
                .map(o -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderCode", o.getOrderCode());
                    m.put("orderDate", o.getOrderDate());
                    m.put("status", o.getStatus());
                    m.put("totalKes", o.getTotalAmount());
                    m.put("source", o.getOrderSource() == null ? "DISTRIBUTOR" : o.getOrderSource());
                    m.put("items", o.getItems() == null ? "" : o.getItems().stream()
                            .map(i -> i.getQuantity() + "x " + i.getItemName())
                            .collect(Collectors.joining(", ")));
                    return m;
                }).toList();
        p.put("recentOrders", recent);
        return p;
    }

    private static List<Object> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** True when the shop belongs to this distributor (linked or has ordered from it). */
    public boolean isDistributorShop(Long distributorId, Long shopId) {
        if (shopId.equals(distributorId)) return false;
        Optional<Merchant> shop = merchantRepository.findById(shopId);
        if (shop.isEmpty()) return false;
        if (shop.get().getDistributor() != null && distributorId.equals(shop.get().getDistributor().getId())) return true;
        return shopQueryRepository.orderSummaryByShop(distributorId).stream().anyMatch(r -> shopId.equals(r[0]));
    }

    // ───────────────────────────── reps ─────────────────────────────

    public List<User> repsOf(Long distributorId) {
        return userRepository.findByRoleAndMerchant_Id(UserRole.FIELD_REP, distributorId);
    }

    public List<Map<String, Object>> listReps(Long distributorId) {
        Map<Long, Long> shopCounts = assignmentRepository.findByDistributorId(distributorId).stream()
                .collect(Collectors.groupingBy(RepAssignment::getRepUserId, Collectors.counting()));
        return repsOf(distributorId).stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getUserId());
            m.put("username", u.getUsername());
            m.put("status", u.getStatus());
            m.put("assignedShops", shopCounts.getOrDefault(u.getUserId(), 0L));
            return m;
        }).toList();
    }

    /** Creates a FIELD_REP login for the distributor (used to sign in to the SFA app). */
    @Transactional
    public Map<String, Object> createRep(Long distributorId, String username, String password) {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[A-Za-z0-9._-]{3,40}")) {
            throw new IllegalArgumentException("Username must be 3-40 letters, digits, dots, dashes or underscores");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (userRepository.existsByUsernameIgnoreCase(name)) {
            throw new IllegalArgumentException("Username " + name + " is already taken");
        }
        Merchant distributor = merchantRepository.findById(distributorId)
                .orElseThrow(() -> new IllegalArgumentException("Distributor not found"));

        User rep = new User();
        rep.setUsername(name);
        rep.setPassword(passwordEncoder.encode(password));
        rep.setRole(UserRole.FIELD_REP);
        rep.setStatus(Status.ACTIVE);
        rep.setMerchant(distributor);
        rep.setIsWholesaler(false);
        User saved = userRepository.save(rep);
        return Map.of("userId", saved.getUserId(), "username", saved.getUsername());
    }

    /** Assigns (or with repUserId = null, unassigns) a shop's rep. */
    @Transactional
    public void assignRep(Long distributorId, Long shopId, Long repUserId, Long byUserId) {
        if (!isDistributorShop(distributorId, shopId)) {
            throw new IllegalArgumentException("Shop not found for this distributor");
        }
        Optional<RepAssignment> existing = assignmentRepository.findByDistributorIdAndShopMerchantId(distributorId, shopId);
        if (repUserId == null) {
            existing.ifPresent(assignmentRepository::delete);
            return;
        }
        boolean validRep = repsOf(distributorId).stream().anyMatch(u -> u.getUserId().equals(repUserId));
        if (!validRep) {
            throw new IllegalArgumentException("That user is not one of this distributor's field reps");
        }
        RepAssignment a = existing.orElseGet(() -> RepAssignment.builder()
                .distributorId(distributorId).shopMerchantId(shopId).build());
        a.setRepUserId(repUserId);
        a.setAssignedByUserId(byUserId);
        a.setAssignedAt(LocalDateTime.now());
        assignmentRepository.save(a);
    }
}
