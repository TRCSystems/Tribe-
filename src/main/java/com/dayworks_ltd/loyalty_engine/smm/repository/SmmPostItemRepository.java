package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmPostItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmPostItemRepository extends JpaRepository<SmmPostItem, Long> {
    /** What is on a Reel: the Reader is told these products, the planner answers from them. */
    List<SmmPostItem> findByMerchantIdAndPostId(Long merchantId, String postId);
}
