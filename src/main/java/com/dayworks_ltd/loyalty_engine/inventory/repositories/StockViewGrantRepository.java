package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.StockViewGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface StockViewGrantRepository extends JpaRepository<StockViewGrant, Long> {

    Optional<StockViewGrant> findTopByUserIdAndVerifiedAtIsNullOrderByCreatedAtDesc(Long userId);

    Optional<StockViewGrant> findByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime since);
}
