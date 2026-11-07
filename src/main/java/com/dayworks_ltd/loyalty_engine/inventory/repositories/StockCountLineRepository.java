package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface StockCountLineRepository extends JpaRepository<StockCountLine, Long> {

    List<StockCountLine> findByCloseDayIdOrderByPriorityRankAsc(Long closeDayId);

    List<StockCountLine> findByCloseDayIdAndStatusIn(Long closeDayId, Collection<StockCountStatus> statuses);

    /** Last time each item was physically counted, as rows of [itemCode, max(countedAt)]. */
    @Query("SELECT l.itemCode, MAX(l.countedAt) FROM StockCountLine l " +
            "WHERE l.merchantId = :merchantId AND l.countedAt IS NOT NULL " +
            "GROUP BY l.itemCode")
    List<Object[]> findLastCountedAtByItem(@Param("merchantId") String merchantId);

    /** Shortage lines in a window — used to spot items that go missing repeatedly. */
    @Query("SELECT l FROM StockCountLine l WHERE l.merchantId = :merchantId " +
            "AND l.varianceQuantity < 0 AND l.countedAt >= :since")
    List<StockCountLine> findShortagesSince(@Param("merchantId") String merchantId,
                                            @Param("since") LocalDateTime since);
}
