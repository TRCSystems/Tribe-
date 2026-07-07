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


    @Column(name = "wholesale_price", precision = 10, scale = 2)
    private BigDecimal wholesalePrice;

    @Column(nullable = false)
    private LocalDate recordDate = LocalDate.now();

    // Safe stock calculation
    public void computeAvailableStock() {
        this.availableStock =
                (startingStock != null ? startingStock : 0) +
                        (addedStock != null ? addedStock : 0) -
                        (soldStock != null ? soldStock : 0);
    }

    public void computeTotalSales() {
        if ((totalSales == null || totalSales.compareTo(BigDecimal.ZERO) == 0)
                && unitPrice != null && soldStock != null && soldStock > 0) {
            this.totalSales = unitPrice.multiply(BigDecimal.valueOf(soldStock));
        }
    }
    public void applyRestock(int quantity, BigDecimal buyingPrice) {
        this.addedStock = (this.addedStock != null ? this.addedStock : 0) + quantity;
        this.wholesalePrice = buyingPrice;
        this.lastRestockDate = LocalDateTime.now();
        this.lastUpdated = LocalDateTime.now();
        computeAvailableStock();
    }
}
