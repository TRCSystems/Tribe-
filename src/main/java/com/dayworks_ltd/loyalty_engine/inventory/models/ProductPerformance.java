package com.dayworks_ltd.loyalty_engine.inventory.models;



import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "product_performance",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"merchant_id", "item_code", "period_date"})
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Loose coupling to inventory_stock — no hard FK
    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String itemCode;

    @Column(nullable = false)
    private String itemName;

    @Column(nullable = false)
    private LocalDate periodDate; // daily snapshot

    // Volume
    @Column(nullable = false)
    private Integer unitsSold = 0;

    @Column(nullable = false)
    private Integer unitsRestocked = 0;

    // Revenue
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal grossRevenue = BigDecimal.ZERO; // unitPrice * unitsSold

    // Cost
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal totalCost = BigDecimal.ZERO; // wholesalePrice * unitsSold

    // Profit
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal grossProfit = BigDecimal.ZERO; // grossRevenue - totalCost

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal deductions = BigDecimal.ZERO; // returns, discounts

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal netProfit = BigDecimal.ZERO; // grossProfit - deductions

    // Margin
    @Column(precision = 5, scale = 2, nullable = false)
    private BigDecimal marginPercentage = BigDecimal.ZERO; // (grossProfit / grossRevenue) * 100

    // Unit economics
    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice; // selling price at time of snapshot

    @Column(precision = 10, scale = 2)
    private BigDecimal unitCost; // buying price at time of snapshot

    @Column(nullable = false)
    private LocalDateTime lastUpdated = LocalDateTime.now();

    // Recompute all metrics from raw inputs
    public void recompute(int soldStock, BigDecimal sellingPrice,
                          BigDecimal buyingPrice, BigDecimal deductionAmount) {

        this.unitsSold = soldStock;
        this.unitPrice = sellingPrice;
        this.unitCost = buyingPrice;
        this.deductions = deductionAmount != null ? deductionAmount : BigDecimal.ZERO;

        this.grossRevenue = sellingPrice != null
                ? sellingPrice.multiply(BigDecimal.valueOf(soldStock))
                : BigDecimal.ZERO;

        this.totalCost = buyingPrice != null
                ? buyingPrice.multiply(BigDecimal.valueOf(soldStock))
                : BigDecimal.ZERO;

        this.grossProfit = this.grossRevenue.subtract(this.totalCost);

        this.netProfit = this.grossProfit.subtract(this.deductions);

        this.marginPercentage = this.grossRevenue.compareTo(BigDecimal.ZERO) > 0
                ? this.grossProfit
                .divide(this.grossRevenue, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        this.lastUpdated = LocalDateTime.now();
    }
}