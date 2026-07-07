package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.models.RecurringExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, Long> {

    List<RecurringExpense> findByMerchantIdAndIsActiveTrue(String merchantId);

    @Query("SELECT re FROM RecurringExpense re WHERE re.isActive = true AND re.nextExecutionDate = :today")
    List<RecurringExpense> findDueRecurringExpenses(@Param("today") LocalDate today);
}