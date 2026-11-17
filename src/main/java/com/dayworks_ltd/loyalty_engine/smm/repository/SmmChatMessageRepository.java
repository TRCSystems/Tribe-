package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmmChatMessageRepository extends JpaRepository<SmmChatMessage, Long> {

    /** Meta can deliver the same message twice. */
    boolean existsByMetaMessageId(String metaMessageId);

    List<SmmChatMessage> findTop40ByStatusOrderByIdAsc(SmmChatMessage.Status status);

    /** The whole chat that followed one private reply, oldest first. */
    List<SmmChatMessage> findByReplyIdOrderByIdAsc(Long replyId);
}
