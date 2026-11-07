package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WhatsAppInboundRepository extends JpaRepository<WhatsAppInbound, Long> {

    boolean existsByWaMessageId(String waMessageId);
}
