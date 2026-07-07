package com.dayworks_ltd.loyalty_engine.inventory.services;



import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.ProductPerformance;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.ProductPerformanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductPerformanceService {

    private final ProductPerformanceRepository performanceRepository;

    @Transactional
    public void updatePerformance(Inventory inventory, int quantitySold, BigDecimal deduction) {
        LocalDate today = LocalDate.now();

        // Find today's record or create new one
        ProductPerformance perf = performanceRepository
                .findByMerchantIdAndItemCodeAndPeriodDate(
                        inventory.getMerchantId(),
                        inventory.getItemCode(),
                        today
                )
                .orElse(ProductPerformance.builder()
                        .merchantId(inventory.getMerchantId())
                        .itemCode(inventory.getItemCode())
                        .itemName(inventory.getItemName())
                        .periodDate(today)
                        .unitsSold(0)
                        .unitsRestocked(0)
                        .grossRevenue(BigDecimal.ZERO)
                        .totalCost(BigDecimal.ZERO)
                        .grossProfit(BigDecimal.ZERO)
                        .deductions(BigDecimal.ZERO)
                        .netProfit(BigDecimal.ZERO)
                        .marginPercentage(BigDecimal.ZERO)
                        .lastUpdated(LocalDateTime.now())
                        .build()
                );

        // Resolve prices safely
        BigDecimal sellingPrice = inventory.getUnitPrice() != null
                ? inventory.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal buyingPrice = inventory.getWholesalePrice() != null
                ? inventory.getWholesalePrice() : BigDecimal.ZERO;
        BigDecimal safeDeduction = deduction != null
                ? deduction.abs() : BigDecimal.ZERO;

        // Compute this sale's contribution
        BigDecimal saleRevenue = sellingPrice.multiply(BigDecimal.valueOf(quantitySold));
        BigDecimal saleCost = buyingPrice.multiply(BigDecimal.valueOf(quantitySold));
        BigDecimal saleGrossProfit = saleRevenue.subtract(saleCost);
        BigDecimal saleNetProfit = saleGrossProfit.subtract(safeDeduction);

        // Accumulate onto today's record
        perf.setUnitsSold(perf.getUnitsSold() + quantitySold);
        perf.setGrossRevenue(perf.getGrossRevenue().add(saleRevenue));
        perf.setTotalCost(perf.getTotalCost().add(saleCost));
        perf.setGrossProfit(perf.getGrossProfit().add(saleGrossProfit));
        perf.setDeductions(perf.getDeductions().add(safeDeduction));
        perf.setNetProfit(perf.getNetProfit().add(saleNetProfit));

        // Snapshot unit economics at time of sale
        perf.setUnitPrice(sellingPrice);
        perf.setUnitCost(buyingPrice);

        // Recompute margin percentage from accumulated totals
        perf.setMarginPercentage(
                perf.getGrossRevenue().compareTo(BigDecimal.ZERO) > 0
                        ? perf.getGrossProfit()
                        .divide(perf.getGrossRevenue(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO
        );

        perf.setLastUpdated(LocalDateTime.now());
        performanceRepository.save(perf);

        log.info("ProductPerformance updated for merchant {} item {} — sold: {}, netProfit: {}",
                inventory.getMerchantId(), inventory.getItemCode(), quantitySold, saleNetProfit);
    }

    // Called when stock is received from distributor
    @Transactional
    public void updateRestock(Inventory inventory, int quantityRestocked) {
        LocalDate today = LocalDate.now();

        ProductPerformance perf = performanceRepository
                .findByMerchantIdAndItemCodeAndPeriodDate(
                        inventory.getMerchantId(),
                        inventory.getItemCode(),
                        today
                )
                .orElse(ProductPerformance.builder()
                        .merchantId(inventory.getMerchantId())
                        .itemCode(inventory.getItemCode())
                        .itemName(inventory.getItemName())
                        .periodDate(today)
                        .unitsSold(0)
                        .unitsRestocked(0)
                        .grossRevenue(BigDecimal.ZERO)
                        .totalCost(BigDecimal.ZERO)
                        .grossProfit(BigDecimal.ZERO)
                        .deductions(BigDecimal.ZERO)
                        .netProfit(BigDecimal.ZERO)
                        .marginPercentage(BigDecimal.ZERO)
                        .lastUpdated(LocalDateTime.now())
                        .build()
                );

        perf.setUnitsRestocked(perf.getUnitsRestocked() + quantityRestocked);
        perf.setLastUpdated(LocalDateTime.now());
        performanceRepository.save(perf);

        log.info("ProductPerformance restock updated for merchant {} item {} — restocked: {}",
                inventory.getMerchantId(), inventory.getItemCode(), quantityRestocked);
    }

    // Query methods
    public List<ProductPerformance> getDailyPerformance(String merchantId, LocalDate date) {
        return performanceRepository.findByMerchantIdAndPeriodDate(merchantId, date);
    }

    public List<ProductPerformance> getPeriodPerformance(String merchantId, LocalDate from, LocalDate to) {
        return performanceRepository.findByMerchantIdAndPeriodDateBetween(merchantId, from, to);
    }

    public List<ProductPerformance> getTopPerformers(String merchantId, LocalDate from, LocalDate to) {
        return performanceRepository.findTopPerformersByMerchantAndPeriod(merchantId, from, to);
    }

    public BigDecimal getTotalNetProfit(String merchantId, LocalDate from, LocalDate to) {
        BigDecimal total = performanceRepository.sumNetProfitByMerchantAndPeriod(merchantId, from, to);
        return total != null ? total : BigDecimal.ZERO;
    }
}