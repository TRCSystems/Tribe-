package com.dayworks_ltd.loyalty_engine.inventory.repositories;

import com.dayworks_ltd.loyalty_engine.inventory.DTO.ItemMarginProjection;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.MarginTotalsProjection;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.OrderTypeMarginProjection;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.SaleLineProjection;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    List<Inventory> findByMerchantIdAndRecordDateBetween(
            String merchantId, LocalDate startDate, LocalDate endDate);

    List<Inventory> findByMerchantIdAndRecordDateGreaterThanEqual(
            String merchantId, LocalDate startDate);

    List<Inventory> findByMerchantId(String merchantId);

    Optional<Inventory> findByMerchantIdAndItemNameAndRecordDate(String merchantId, String itemName, LocalDate recordDate);

    List<Inventory> findByMerchantIdAndClosingStockLessThan(String merchantId, int threshold);

    Optional<Inventory> findByMerchantIdAndItemCode(String merchantId, String itemCode);

    List<Inventory> findByMerchantIdAndRecordDate(String merchantId, LocalDate date);

    Optional<Inventory> findFirstByMerchantIdAndRecordDate(String merchantId, LocalDate date);


    Optional<Inventory> findByMerchantIdAndItemCodeAndRecordDate(
            String merchantId, String itemCode, LocalDate date);

    Optional<Inventory> findByIdAndMerchantId(Long id, String merchantId);

    Optional<Inventory> findByMerchantIdAndItemName(String merchantId, String itemName);

    @Query("SELECT i FROM Inventory i WHERE i.merchantId = :merchantId AND i.isActive = true")
    List<Inventory> findActiveByMerchantId(@Param("merchantId") String merchantId);

    List<Inventory> findByMerchantIdAndIsActive(String merchantId, Boolean isActive);

    boolean existsByMerchantIdAndItemName(String merchantId, String itemName);

    // Optional: case-insensitive version (very useful for names)
    @Query("SELECT COUNT(i) > 0 FROM Inventory i " +
            "WHERE i.merchantId = :merchantId AND LOWER(i.itemName) = LOWER(:itemName)")
    boolean existsByMerchantIdAndItemNameIgnoreCase(
            @Param("merchantId") String merchantId,
            @Param("itemName") String itemName);


    @Query(value = """
    SELECT 
        SUM(quantity) as unitsSold,
        SUM(COALESCE(unit_price * quantity + discount, 0)) as grossRevenue,
        SUM(COALESCE(unit_cost * quantity, 0)) as totalCost,
        SUM(COALESCE((unit_price * quantity + discount) - (unit_cost * quantity), 0)) as grossMargin,
        ROUND(
            SUM(COALESCE((unit_price * quantity + discount) - (unit_cost * quantity), 0)) 
            / NULLIF(SUM(unit_price * quantity + discount), 0) * 100, 2
        ) as marginPercentage
    FROM sale_transactions
    WHERE merchant_id = :merchantId AND sale_date = :date
    """, nativeQuery = true)
    MarginTotalsProjection getDailyTotal(@Param("merchantId") String merchantId, @Param("date") LocalDate date);

    @Query(value = """
    SELECT 
        order_type as orderType,
        SUM(quantity) as unitsSold,
        SUM(COALESCE(unit_price * quantity + discount, 0)) as grossRevenue,
        SUM(COALESCE(unit_cost * quantity, 0)) as totalCost,
        SUM(COALESCE((unit_price * quantity + discount) - (unit_cost * quantity), 0)) as grossMargin,
        ROUND(
            SUM(COALESCE((unit_price * quantity + discount) - (unit_cost * quantity), 0)) 
            / NULLIF(SUM(unit_price * quantity + discount), 0) * 100, 2
        ) as marginPercentage
    FROM sale_transactions
    WHERE merchant_id = :merchantId AND sale_date = :date
    GROUP BY order_type
    """, nativeQuery = true)
    List<OrderTypeMarginProjection> getMarginByOrderType(@Param("merchantId") String merchantId, @Param("date") LocalDate date);

    @Query(value = """
    SELECT 
        item_code as itemCode,
        item_name as itemName,
        order_type as orderType,
        SUM(quantity) as unitsSold,
        SUM(COALESCE(unit_price * quantity + discount, 0)) as grossRevenue,
        SUM(COALESCE(unit_cost * quantity, 0)) as totalCost,
        SUM(COALESCE((unit_price * quantity + discount) - (unit_cost * quantity), 0)) as grossMargin,
        ROUND(
            SUM(COALESCE((unit_price * quantity + discount) - (unit_cost * quantity), 0)) 
            / NULLIF(SUM(unit_price * quantity + discount), 0) * 100, 2
        ) as marginPercentage
    FROM sale_transactions
    WHERE merchant_id = :merchantId AND sale_date = :date
    GROUP BY item_code, item_name, order_type
    ORDER BY grossMargin DESC
    """, nativeQuery = true)
    List<ItemMarginProjection> getMarginByItem(@Param("merchantId") String merchantId, @Param("date") LocalDate date);

    @Query("""
    SELECT 
        st.transactionRef as transactionRef,
        st.saleDateTime as saleDatetime,
        st.customerPhone as customerPhone,
        st.itemCode as itemCode,
        st.itemName as itemName,
        st.quantity as quantity,
        st.unitPrice as unitPrice,
        st.totalPrice as totalPrice,
        st.orderType as orderType
    FROM SaleTransaction st
    WHERE st.merchantId = :merchantId AND st.saleDate = :date
    ORDER BY st.transactionRef, st.saleDateTime
    """)
    List<SaleLineProjection> getReconciliationLines(@Param("merchantId") String merchantId, @Param("date") LocalDate date);
}
