package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmCaptionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmCaptionItemRepository extends JpaRepository<SmmCaptionItem, Long> {

    boolean existsByMerchantIdAndPostId(Long merchantId, String postId);

    /** This seller's range from recent posts, newest first; capped so the facts given to a model stay short. */
    List<SmmCaptionItem> findTop60ByMerchantIdOrderByIdDesc(Long merchantId);
}
