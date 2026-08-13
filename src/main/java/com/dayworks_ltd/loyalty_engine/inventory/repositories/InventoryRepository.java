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

//    List<Inventory> findByMerchantId(String merchantId);

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

    @Query("SELECT i FROM Inventory i WHERE i.merchantId = :merchantId")
    List<Inventory> findByMerchantId(@Param("merchantId") String merchantId);

    // Optional: case-insensitive version (very useful for names)
    @Query("SELECT COUNT(i) > 0 FROM Inventory i " +
            "WHERE i.merchantId = :merchantId AND LOWER(i.itemName) = LOWER(:itemName)")
    boolean existsByMerchantIdAndItemNameIgnoreCase(
            @Param("merchantId") String merchantId,
            @Param("itemName") String itemName);

    @Query(value = """
    SELECT 
        SUM(quantity) as unitsSold,
        SUM(unit_price * quantity + COALESCE(discount, 0)) as grossRevenue,
        SUM(COALESCE(unit_cost * quantity, 0)) as totalCost,
        SUM((unit_price * quantity + COALESCE(discount, 0)) - COALESCE(unit_cost * quantity, 0)) as grossMargin,
        ROUND(
            SUM((unit_price * quantity + COALESCE(discount, 0)) - COALESCE(unit_cost * quantity, 0))
            / NULLIF(SUM(unit_price * quantity + COALESCE(discount, 0)), 0) * 100, 2
        ) as marginPercentage
    FROM sale_transactions
    WHERE merchant_id = :merchantId AND sale_date = :date
    """, nativeQuery = true)
    MarginTotalsProjection getDailyTotal(@Param("merchantId") String merchantId, @Param("date") LocalDate date);

    @Query(value = """
    SELECT 
        order_type as orderType,
        SUM(quantity) as unitsSold,
        SUM(unit_price * quantity + COALESCE(discount, 0)) as grossRevenue,
        SUM(COALESCE(unit_cost * quantity, 0)) as totalCost,
        SUM((unit_price * quantity + COALESCE(discount, 0)) - COALESCE(unit_cost * quantity, 0)) as grossMargin,
        ROUND(
            SUM((unit_price * quantity + COALESCE(discount, 0)) - COALESCE(unit_cost * quantity, 0))
            / NULLIF(SUM(unit_price * quantity + COALESCE(discount, 0)), 0) * 100, 2
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
        SUM(unit_price * quantity + COALESCE(discount, 0)) as grossRevenue,
        SUM(COALESCE(unit_cost * quantity, 0)) as totalCost,
        SUM((unit_price * quantity + COALESCE(discount, 0)) - COALESCE(unit_cost * quantity, 0)) as grossMargin,
        ROUND(
            SUM((unit_price * quantity + COALESCE(discount, 0)) - COALESCE(unit_cost * quantity, 0))
            / NULLIF(SUM(unit_price * quantity + COALESCE(discount, 0)), 0) * 100, 2
        ) as marginPercentage
    FROM sale_transactions
    WHERE merchant_id = :merchantId AND sale_date = :date
    GROUP BY item_code, item_name, order_type
    ORDER BY grossMargin DESC
    """, nativeQuery = true)
    List<ItemMarginProjection> getMarginByItem(@Param("merchantId") String merchantId, @Param("date") LocalDate date);
    @Query(value = """
    SELECT\s
        COALESCE(o.payment_reference, st.transaction_ref) AS transactionRef,
        st.sale_datetime AS saleDatetime,
        st.customer_phone AS customerPhone,
        st.item_code AS itemCode,
        st.item_name AS itemName,
        st.quantity AS quantity,
        st.unit_price AS unitPrice,
        st.total_price AS totalPrice,
        st.order_type AS orderType,
        m.business_name AS merchantName,
        m.business_phone AS merchantPhone
    FROM sale_transactions st\s
    LEFT JOIN stock_transfers stf ON stf.transfer_code = st.transaction_ref
    LEFT JOIN orders o\s
           ON (o.stock_transfer_id = stf.id OR o.order_code = st.transaction_ref)
    LEFT JOIN merchants m ON m.id = stf.recipient_id
    WHERE st.merchant_id = :merchantId\s
      AND st.sale_date = :date
    ORDER BY COALESCE(o.payment_reference, st.transaction_ref), st.sale_datetime
   \s""",
            nativeQuery = true)
    List<SaleLineProjection> getReconciliationLines(
            @Param("merchantId") String merchantId,
            @Param("date") LocalDate date);
}
