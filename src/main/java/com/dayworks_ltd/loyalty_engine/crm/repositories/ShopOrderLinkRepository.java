package com.dayworks_ltd.loyalty_engine.crm.repositories;

import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShopOrderLinkRepository extends JpaRepository<ShopOrderLink, Long> {

    Optional<ShopOrderLink> findByTokenHash(String tokenHash);

    /** Locks the link row so two submits from the same link can't both create an order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ShopOrderLink l WHERE l.tokenHash = :tokenHash")
    Optional<ShopOrderLink> lockByTokenHash(@Param("tokenHash") String tokenHash);
}
