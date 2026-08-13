package com.dayworks_ltd.loyalty_engine.credit_engine.repository;


import com.dayworks_ltd.loyalty_engine.credit_engine.model.RawCallbackLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawCallbackLogRepository extends JpaRepository<RawCallbackLog, Long> {
}

