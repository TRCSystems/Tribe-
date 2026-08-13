package com.dayworks_ltd.loyalty_engine.credit_engine.repository;



import com.dayworks_ltd.loyalty_engine.credit_engine.model.ReserveAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReserveAccountRepository extends JpaRepository<ReserveAccount, Long> {

    /**
     * Row-locked read — required before any allocate/release operation to
     * prevent two concurrent consignment orders from both passing the
     * available-capacity check and jointly overdrawing the reserve.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReserveAccount r WHERE r.id = :id")
    Optional<ReserveAccount> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReserveAccount r WHERE r.sourceName = :sourceName")
    Optional<ReserveAccount> findBySourceNameForUpdate(@Param("sourceName") String sourceName);



}
