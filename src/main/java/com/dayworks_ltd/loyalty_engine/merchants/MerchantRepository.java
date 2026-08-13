package com.dayworks_ltd.loyalty_engine.merchants;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.util.List;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long>{
    Optional<Merchant> findByTillNumber(String tillNumber);
    boolean existsByTillNumber(String tillNumber);

    @Query(value = """
        SELECT m.* FROM merchants m
        INNER JOIN user u ON u.merchant_id = m.id
        WHERE m.business_type = :businessType 
          AND u.is_wholesaler = true
        """,
            nativeQuery = true)
    List<Merchant> findLiquorWholesalers(@Param("businessType") String businessType);

    List<Merchant> findByBusinessPhone(String businessPhone);

    List<Merchant> findByBusinessNameContainingIgnoreCase(String businessName);

    @Query(value = "SELECT business_name FROM merchants WHERE UPPER(business_type) = 'LIQUOR'",
            nativeQuery = true)
    List<String> findBusinessNamesByLiquorType();


}
