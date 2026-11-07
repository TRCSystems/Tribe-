package com.dayworks_ltd.loyalty_engine.crm.repositories;

import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Read-only queries over orders for the CRM shop list. */
@org.springframework.stereotype.Repository
public interface CrmShopQueryRepository extends Repository<Order, Long> {

    /** [shopId, orderCount, lastOrderDate, totalAmount] for non-cancelled orders to this distributor. */
    @Query("SELECT o.merchant.id, COUNT(o), MAX(o.orderDate), SUM(o.totalAmount) FROM Order o " +
            "WHERE o.distributor.id = :distributorId AND o.status <> com.dayworks_ltd.loyalty_engine.common.OrderStatus.CANCELLED " +
            "GROUP BY o.merchant.id")
    List<Object[]> orderSummaryByShop(@Param("distributorId") Long distributorId);

    /** All orders (any status) with their lines for a distributor since a moment — input for shop stats. */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items " +
            "WHERE o.distributor.id = :distributorId AND o.orderDate >= :since")
    List<Order> ordersWithItemsSince(@Param("distributorId") Long distributorId,
                                     @Param("since") java.time.LocalDateTime since);

    /** [shopId, firstOrderDate, allTimeOrderCount] for non-cancelled orders. */
    @Query("SELECT o.merchant.id, MIN(o.orderDate), COUNT(o) FROM Order o " +
            "WHERE o.distributor.id = :distributorId AND o.status <> com.dayworks_ltd.loyalty_engine.common.OrderStatus.CANCELLED " +
            "GROUP BY o.merchant.id")
    List<Object[]> firstOrderByShop(@Param("distributorId") Long distributorId);

    /** One shop's orders with lines since a moment (for the shop profile). */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items " +
            "WHERE o.distributor.id = :distributorId AND o.merchant.id = :shopId AND o.orderDate >= :since")
    List<Order> shopOrdersWithItemsSince(@Param("distributorId") Long distributorId,
                                         @Param("shopId") Long shopId,
                                         @Param("since") java.time.LocalDateTime since);

    @Query("SELECT DISTINCT o.distributor.id FROM Order o")
    List<Long> distributorIdsWithOrders();
}
