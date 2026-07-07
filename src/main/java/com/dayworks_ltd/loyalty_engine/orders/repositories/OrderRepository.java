package com.dayworks_ltd.loyalty_engine.orders.repositories;

import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderCode(String orderCode);

    List<Order> findByMerchantIdOrderByOrderDateDesc(Long merchantId);

    List<Order> findByDistributorIdOrderByOrderDateDesc(Long distributorId);

    @Query("SELECT o FROM Order o WHERE o.distributor.id = :distributorId AND o.status = :status AND o.paymentMode = :paymentMode")
    List<Order> findByDistributorAndStatusAndPaymentMode(
            @Param("distributorId") Long distributorId,
            @Param("status") OrderStatus status,
            @Param("paymentMode") PaymentMode paymentMode
    );

    List<Order> findByDistributorIdAndStatus(Long distributorId, OrderStatus status);

    List<Order> findByMerchantIdAndStatus(Long merchantId, OrderStatus status);

    Optional<Order> findByCheckoutRequestId(String checkoutRequestId);

    List<Order> findByDistributorIdAndStatusOrderByOrderDateAsc(Long distributorId, OrderStatus status);
}