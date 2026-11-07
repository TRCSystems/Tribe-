package com.dayworks_ltd.loyalty_engine.inventory.models;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_stock")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Inventory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private String merchantId;
    @Column(nullable = false) private String itemName;
    @Column(nullable = false) private String itemCode;

    // Stock
    @Column(nullable = false) private Integer startingStock = 0;
    @Column(nullable = false) private Integer addedStock = 0;
    @Column(nullable = false) private Integer soldStock = 0;
    @Column(nullable = false) private Integer availableStock = 0;
    @Column(name = "closing_stock", nullable = false) private Integer closingStock = 0;

    // Money
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal totalSales = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal grossSales = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal netlSales = BigDecimal.ZERO;



    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal deductions = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2) private BigDecimal unitCost;
    @Column(precision = 10, scale = 2) private BigDecimal unitPrice;
    @Column(length = 255)
    private String expenseNote;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "meta_retailer_id")
    private String metaRetailerId;

    @Column(name = "meta_synced")
    private Boolean metaSynced = false;

    @Column(name = "meta_last_sync_at")
    private LocalDateTime metaLastSyncAt;

    @Column(name = "product_image_url")
    private String productImageUrl;

    @Column(name = "product_description", columnDefinition = "TEXT")
    private String productDescription;

    @Column(name = "product_category")
    private String productCategory;

    @Column(name = "batch_number")
    private String batchNumber;  // For traceability (lot/batch ID)

    @Column(name = "supplier_name")
    private String supplierName;  // Who supplied this batch

    @Column(name = "date_purchased")
    private LocalDate datePurchased;  // When this batch was acquired

    @Column(name = "expiry_date")
    private LocalDate expiryDate;  // For perishables (null if N/A)

    @Column(name = "reorder_level")
    private Integer reorderLevel = 0;  // Min stock to trigger reorder alert

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated = LocalDateTime.now();

    @Column(name = "last_restock_date")
    private LocalDateTime lastRestockDate;

    @Column(name = "product_brand")
    private String productBrand;

    /** Other names customers use for the item (e.g. "unga, maize flour"), for matching typed lists. */
    @Column(length = 500)
    private String keywords;

    /** GOOD (default when null) or SERVICE: a salon treatment or nail set is booked, not counted on a shelf. */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", length = 10)
    private ItemType itemType;

    /**
     * Colour and size of this row when a product comes in several (e.g. the same dress in green M and black L).
     * Each colour/size is its own row, grouped under parentCode, so stock is counted per colour and size.
     */
    @Column(length = 40)
    private String colour;

    @Column(length = 20)
    private String size;

    /** Item code of the product this colour/size belongs to; null for a product without variants. */
    @Column(name = "parent_code", length = 100)
    private String parentCode;

    public enum ItemType { GOOD, SERVICE }

    /** Services have no stock to count. */
    public boolean isService() {
        return itemType == ItemType.SERVICE;
    }


    @Column(name = "wholesale_price", precision = 10, scale = 2)
    private BigDecimal wholesalePrice;

    @Column(nullable = false)
    private LocalDate recordDate = LocalDate.now();

    /*
     * Stock rule: availableStock is the truth. Every movement changes it by exactly the moved quantity
     * (addStock / removeStock), and startingStock is re-derived so that
     *     startingStock + addedStock - soldStock == availableStock
     * always holds. Recomputing availableStock from the totals is what made stock jump (e.g. 10 + 20 = 35)
     * after any path had set availableStock directly (transfers, imports, edits).
     */

    /** Stock coming in (restock, delivery, transfer received). */
    public void addStock(int quantity) {
        this.addedStock = (addedStock != null ? addedStock : 0) + quantity;
        this.availableStock = (availableStock != null ? availableStock : 0) + quantity;
        this.closingStock = this.availableStock;
        syncTotals();
    }

    /** Stock going out. Sales count towards soldStock; other removals (transfers out) only reduce stock. */
    public void removeStock(int quantity, boolean isSale) {
        if (isSale) {
            this.soldStock = (soldStock != null ? soldStock : 0) + quantity;
        }
        this.availableStock = (availableStock != null ? availableStock : 0) - quantity;
        this.closingStock = this.availableStock;
        syncTotals();
    }

    /** Keeps the day's totals consistent with the current stock without ever changing the current stock. */
    public void syncTotals() {
        int available = availableStock != null ? availableStock : 0;
        this.startingStock = available - (addedStock != null ? addedStock : 0) + (soldStock != null ? soldStock : 0);
    }

    /**
     * @deprecated Kept for older callers. It no longer recomputes availableStock (that caused stock jumps);
     * it only re-aligns the totals with the current stock. Use addStock / removeStock to move stock.
     */
    @Deprecated
    public void computeAvailableStock() {
        if (availableStock == null) {
            this.availableStock = (startingStock != null ? startingStock : 0)
                    + (addedStock != null ? addedStock : 0)
                    - (soldStock != null ? soldStock : 0);
        }
        syncTotals();
    }

    public void computeTotalSales() {
        if ((totalSales == null || totalSales.compareTo(BigDecimal.ZERO) == 0)
                && unitPrice != null && soldStock != null && soldStock > 0) {
            this.totalSales = unitPrice.multiply(BigDecimal.valueOf(soldStock));
        }
    }
    public void applyRestock(int quantity, BigDecimal buyingPrice) {
        addStock(quantity);
        this.wholesalePrice = buyingPrice;
        this.lastRestockDate = LocalDateTime.now();
        this.lastUpdated = LocalDateTime.now();
    }
}
