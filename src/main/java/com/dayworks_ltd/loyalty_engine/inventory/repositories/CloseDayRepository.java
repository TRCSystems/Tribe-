package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.enums.CloseDayStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CloseDayRepository extends JpaRepository<CloseDay, Long> {

    Optional<CloseDay> findByMerchantIdAndTradingDate(String merchantId, LocalDate tradingDate);

    /** The shop's most recent finished count before the given day. */
    Optional<CloseDay> findTopByMerchantIdAndTradingDateBeforeAndClosedAtIsNotNullOrderByTradingDateDesc(
            String merchantId, LocalDate tradingDate);

    List<CloseDay> findByTradingDateAndStatusIn(LocalDate tradingDate, Collection<CloseDayStatus> statuses);


    List<CloseDay> findByMerchantIdAndTradingDateBetweenOrderByTradingDateDesc(
            String merchantId, LocalDate from, LocalDate to);
}
