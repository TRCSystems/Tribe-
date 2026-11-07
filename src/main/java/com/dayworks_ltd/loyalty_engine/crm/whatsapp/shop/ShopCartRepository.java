package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface ShopCartRepository extends JpaRepository<ShopCart, Long> {

    Optional<ShopCart> findFirstByPhoneAndStoreMerchantIdAndStatusInOrderByIdDesc(
            String phone, Long storeMerchantId, Collection<ShopCart.Status> statuses);

    Optional<ShopCart> findFirstByOrderCode(String orderCode);

    /** Orders placed from this number at this store, newest first (for "cancel the last two orders"). */
    java.util.List<ShopCart> findByPhoneAndStoreMerchantIdAndStatusOrderByIdDesc(
            String phone, Long storeMerchantId, ShopCart.Status status);

    /** Locks the cart so a double tap on "Confirm" can't create two orders. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShopCart c WHERE c.id = :id")
    Optional<ShopCart> lockById(@Param("id") Long id);
}
