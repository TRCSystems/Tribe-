package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.HeldReportSms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HeldReportSmsRepository extends JpaRepository<HeldReportSms, Long> {

    List<HeldReportSms> findByApproverPhoneAndStatusOrderByCreatedAtAsc(String approverPhone, HeldReportSms.Status status);
}
