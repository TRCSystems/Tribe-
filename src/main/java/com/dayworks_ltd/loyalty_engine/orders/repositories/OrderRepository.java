package com.dayworks_ltd.loyalty_engine.orders.repositories;

import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    Page<Order> findByDistributorIdAndStatusAndOrderDateBetween(
            Long distributorId, OrderStatus status, LocalDateTime start, LocalDateTime end, Pageable pageable);

    Page<Order> findByDistributorIdAndStatusAndPaymentModeAndOrderDateBetween(
            Long distributorId, OrderStatus status, PaymentMode paymentMode, LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<Order> findByDistributorIdAndStatus(Long distributorId, OrderStatus status);

    List<Order> findByMerchantIdAndStatus(Long merchantId, OrderStatus status);

    Optional<Order> findByCheckoutRequestId(String checkoutRequestId);

    List<Order> findByDistributorIdAndStatusOrderByOrderDateAsc(Long distributorId, OrderStatus status);
    @Query("SELECT COUNT(o) FROM Order o WHERE o.merchant.id = :merchantId")
    long countOrdersByMerchant(@Param("merchantId") Long merchantId);

    @Query("""
        SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o
        WHERE o.merchant.id = :merchantId
        AND o.status IN (com.dayworks_ltd.loyalty_engine.common.OrderStatus.PAID,
                          com.dayworks_ltd.loyalty_engine.common.OrderStatus.FULFILLED,
                          com.dayworks_ltd.loyalty_engine.common.OrderStatus.RECEIVED)
    """)
    BigDecimal sumPaidVolumeByMerchant(@Param("merchantId") Long merchantId);

    @Query("""
        SELECT COUNT(o) FROM Order o
        WHERE o.merchant.id = :merchantId AND o.status = com.dayworks_ltd.loyalty_engine.common.OrderStatus.PAYMENT_FAILED
    """)
    long countFailedByMerchant(@Param("merchantId") Long merchantId);

    @Query("SELECT MIN(o.orderDate) FROM Order o WHERE o.merchant.id = :merchantId")
    Optional<LocalDateTime> findFirstOrderDate(@Param("merchantId") Long merchantId);

    @Query("""
    SELECT DISTINCT o.merchant.id
    FROM Order o
    WHERE o.merchant.businessType = 'LIQUOR'
    """)
    List<Long> findDistinctMerchantIds();
}