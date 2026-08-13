package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.*;
import com.dayworks_ltd.loyalty_engine.inventory.models.*;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.*;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InventoryService {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private WholesalePriceConfigRepository wholesalePriceConfigRepository;
    @Autowired
    private DailySalesSummaryRepository dailySalesSummaryRepository;

    private final ExpenseRepository expenseRepository;

    private final RecurringExpenseRepository recurringExpenseRepository;

    private final InventoryStockAuditRepository auditRepository;

    private final Logger logger = LoggerFactory.getLogger(InventoryService.class);

    public InventoryService(ExpenseRepository expenseRepository, RecurringExpenseRepository recurringExpenseRepository, InventoryStockAuditRepository auditRepository) {
        this.expenseRepository = expenseRepository;
        this.recurringExpenseRepository = recurringExpenseRepository;
        this.auditRepository = auditRepository;
    }

    public Inventory getInventoryItemById(Long id)
    {
        Optional<Inventory> item = inventoryRepository.findById(id);
        return item.isPresent() ? item.get() : null;
    }
    public MarginReportDto getMarginReport(String merchantId, LocalDate date) {
        MarginTotalsProjection totalProj = inventoryRepository.getDailyTotal(merchantId, date);
        List<OrderTypeMarginProjection> orderTypeProj = inventoryRepository.getMarginByOrderType(merchantId, date);
        List<ItemMarginProjection> itemProj = inventoryRepository.getMarginByItem(merchantId, date);

        MarginReportDto report = new MarginReportDto();
        report.setDate(date);
        report.setMerchantId(merchantId);
        report.setDailyTotal(mapTotals(totalProj));
        report.setByOrderType(orderTypeProj.stream().map(this::mapOrderType).toList());
        report.setByItem(itemProj.stream().map(this::mapItem).toList());
        return report;
    }

    private MarginTotalsDto mapTotals(MarginTotalsProjection p) {
        MarginTotalsDto dto = new MarginTotalsDto();
        dto.setUnitsSold(p.getUnitsSold() != null ? p.getUnitsSold() : 0);
        dto.setGrossRevenue(p.getGrossRevenue() != null ? p.getGrossRevenue() : BigDecimal.ZERO);
        dto.setTotalCost(p.getTotalCost() != null ? p.getTotalCost() : BigDecimal.ZERO);
        dto.setGrossMargin(p.getGrossMargin() != null ? p.getGrossMargin() : BigDecimal.ZERO);
        dto.setMarginPercentage(p.getMarginPercentage() != null ? p.getMarginPercentage() : BigDecimal.ZERO);
        return dto;
    }

    private OrderTypeMarginDto mapOrderType(OrderTypeMarginProjection p) {
        OrderTypeMarginDto dto = new OrderTypeMarginDto();
        dto.setOrderType(p.getOrderType());
        dto.setUnitsSold(p.getUnitsSold());
        dto.setGrossRevenue(p.getGrossRevenue());
        dto.setTotalCost(p.getTotalCost());
        dto.setGrossMargin(p.getGrossMargin());
        dto.setMarginPercentage(p.getMarginPercentage());
        return dto;
    }

    private ItemMarginDto mapItem(ItemMarginProjection p) {
        ItemMarginDto dto = new ItemMarginDto();
        dto.setItemCode(p.getItemCode());
        dto.setItemName(p.getItemName());
        dto.setOrderType(p.getOrderType());
        dto.setUnitsSold(p.getUnitsSold());
        dto.setGrossRevenue(p.getGrossRevenue());
        dto.setTotalCost(p.getTotalCost());
        dto.setGrossMargin(p.getGrossMargin());
        dto.setMarginPercentage(p.getMarginPercentage());
        return dto;
    }


    @Transactional
    public void importFromExcel(MultipartFile file, String merchantId) {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new RuntimeException("File has no name");
        }

        try {
            LocalDate today = LocalDate.now();

            if (filename.toLowerCase().endsWith(".csv")) {
                // ==================== CSV HANDLING ====================
                try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                    String line;
                    boolean isHeader = true;

                    while ((line = br.readLine()) != null) {
                        if (isHeader) {
                            isHeader = false;
                            continue; // skip header
                        }

                        String[] values = line.split(",");
                        if (values.length < 3) {
                            // Skip malformed lines
                            continue;
                        }

                        String itemName = values[0].trim();
                        if (itemName.isEmpty()) continue;

                        double unitPrice = parseDouble(values[1].trim());
                        double unitCost = 0.0;
                        int startingStock = 0;

                        if (values.length >= 4) {
                            // 4 columns: Item, UnitPrice, UnitCost, StartingStock
                            unitCost = parseDouble(values[2].trim());
                            startingStock = parseInt(values[3].trim());
                        } else {
                            // 3 columns: Item, UnitPrice, StartingStock
                            startingStock = parseInt(values[2].trim());
                        }

                        String itemCode = merchantId + "-" + itemName.replaceAll("\\s+", "").toUpperCase();

                        Optional<Inventory> existingOpt = inventoryRepository
                                .findByMerchantIdAndItemCode(merchantId, itemCode);

                        Inventory inv;
                        if (existingOpt.isPresent()) {
                            inv = existingOpt.get();
                        } else {
                            inv = Inventory.builder()
                                    .merchantId(merchantId)
                                    .itemName(itemName)
                                    .itemCode(itemCode)
                                    .addedStock(0)
                                    .soldStock(0)
                                    .totalSales(BigDecimal.ZERO)
                                    .grossSales(BigDecimal.ZERO)
                                    .netlSales(BigDecimal.ZERO)
                                    .deductions(BigDecimal.ZERO)
                                    .expenseNote("")
                                    .recordDate(today)
                                    .closingStock(startingStock)
                                    .build();
                        }

                        inv.setUnitPrice(BigDecimal.valueOf(unitPrice));
                        inv.setUnitCost(BigDecimal.valueOf(unitCost));
                        inv.setStartingStock(startingStock);
                        inv.setAvailableStock(startingStock);
                        inv.setRecordDate(today);

                        inventoryRepository.save(inv);
                    }
                }

            } else if (filename.toLowerCase().endsWith(".xlsx")) {
                // ==================== XLSX HANDLING ====================
                try (InputStream is = file.getInputStream();
                     Workbook workbook = new XSSFWorkbook(is)) {

                    Sheet sheet = workbook.getSheetAt(0);
                    for (Row row : sheet) {
                        if (row.getRowNum() == 0) continue; // skip header

                        if (row.getCell(0) == null || row.getCell(0).toString().trim().isEmpty()) {
                            continue; // skip empty rows
                        }

                        String itemName = getStringCellValue(row, 0);
                        double unitPrice = getNumericValue(row, 1);
                        double unitCost = 0.0;
                        int startingStock = 0;

                        if (row.getPhysicalNumberOfCells() >= 4) {
                            unitCost = getNumericValue(row, 2);
                            startingStock = (int) getNumericValue(row, 3);
                        } else {
                            startingStock = (int) getNumericValue(row, 2);
                        }

                        String itemCode = merchantId + "-" + itemName.replaceAll("\\s+", "").toUpperCase();

                        Optional<Inventory> existingOpt = inventoryRepository
                                .findByMerchantIdAndItemCode(merchantId, itemCode);

                        Inventory inv;
                        if (existingOpt.isPresent()) {
                            inv = existingOpt.get();
                        } else {
                            inv = Inventory.builder()
                                    .merchantId(merchantId)
                                    .itemName(itemName)
                                    .itemCode(itemCode)
                                    .addedStock(0)
                                    .soldStock(0)
                                    .totalSales(BigDecimal.ZERO)
                                    .grossSales(BigDecimal.ZERO)
                                    .netlSales(BigDecimal.ZERO)
                                    .deductions(BigDecimal.ZERO)
                                    .expenseNote("")
                                    .recordDate(today)
                                    .closingStock(-1)
                                    .build();
                        }

                        inv.setUnitPrice(BigDecimal.valueOf(unitPrice));
                        inv.setUnitCost(BigDecimal.valueOf(unitCost));
                        inv.setStartingStock(startingStock);
                        inv.setAvailableStock(startingStock);
                        inv.setRecordDate(today);

                        inventoryRepository.save(inv);
                    }
                }
            } else {
                throw new RuntimeException("Unsupported file format. Only .csv and .xlsx are allowed.");
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to import file: " + e.getMessage(), e);
        }
    }

    @Transactional
    public BatchAddResult batchAddFromDefaults(List<DefaultProductSelectionDto> selections, String merchantId) {
        logger.info("batchAddFromDefaults | merchantId={} | itemCount={}", merchantId, selections != null ? selections.size() : 0);

        int added = 0;
        int skipped = 0;
        LocalDate today = LocalDate.now();

        for (DefaultProductSelectionDto sel : selections) {
            String name = (sel.getProductName() != null) ? sel.getProductName().trim() : "";
            String code = (sel.getProductCode() != null) ? sel.getProductCode().trim() : "";

            if (name.isEmpty() || code.isEmpty()) {
                skipped++;
                continue;
            }

            String itemCode = merchantId + "-" + code.toUpperCase();

            if (inventoryRepository.existsByMerchantIdAndItemName(merchantId, itemCode)) {
                skipped++;
                continue;
            }

            Inventory newItem = Inventory.builder()
                    .merchantId(merchantId)
                    .itemName(name)
                    .itemCode(itemCode)

                    // ── New logic for stock and price ─────────────────────────────────────
                    .startingStock(sel.getStartingStock() != null ? sel.getStartingStock() : 0)
                    .addedStock(0)
                    .soldStock(0)
                    .availableStock(sel.getStartingStock() != null ? sel.getStartingStock() : 0)
                    .closingStock(sel.getStartingStock() != null ? sel.getStartingStock() : 0)

                    .unitPrice(sel.getUnitPrice() != null ? sel.getUnitPrice() : BigDecimal.ZERO)
                    .unitCost(BigDecimal.ZERO)  // still default – or make optional later

                    .totalSales(BigDecimal.ZERO)
                    .grossSales(BigDecimal.ZERO)
                    .netlSales(BigDecimal.ZERO)
                    .deductions(BigDecimal.ZERO)
                    .recordDate(today)
                    .isActive(true)
                    .reorderLevel(10)
                    .lastUpdated(LocalDateTime.now())
                    .build();

            inventoryRepository.save(newItem);
            added++;
        }

        return new BatchAddResult(added, skipped);
    }

    @Transactional
    public List<Inventory> recordMultipleSales(SaleRequest request) {
        List<Inventory> updatedItems = new ArrayList<>();

        for (SaleItemRequest itemReq : request.getItems()) {
            if (itemReq.getQuantity() <= 0) {
                throw new IllegalArgumentException("Invalid quantity for item ID " + itemReq.getInventoryId());
            }
            Inventory item = inventoryRepository.findById(itemReq.getInventoryId())
                    .orElseThrow(() -> new RuntimeException("Item not found"));

            // snapshot BEFORE
            int availBefore = item.getAvailableStock();
            int soldBefore = item.getSoldStock() == null ? 0 : item.getSoldStock();
            BigDecimal totalBefore = item.getTotalSales() == null ? BigDecimal.ZERO : item.getTotalSales();

            LocalDate today = LocalDate.now();
            if (!today.equals(item.getRecordDate())) {
                item.setRecordDate(today);
                item.setStartingStock(item.getAvailableStock());
                item.setSoldStock(0);
                item.setAddedStock(0);
                item.setDeductions(BigDecimal.ZERO);
                item.setTotalSales(BigDecimal.ZERO);
            }

            int newSold = (item.getSoldStock() == null ? 0 : item.getSoldStock()) + itemReq.getQuantity();
            int newAvailable = item.getAvailableStock() - itemReq.getQuantity();

            if (newAvailable < 0) {
                throw new IllegalArgumentException("Not enough stock for " + item.getItemName());
            }

            BigDecimal saleAmount = item.getUnitPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            item.setSoldStock(newSold);
            item.setAvailableStock(newAvailable);
            item.setClosingStock(newAvailable);
            item.setTotalSales(item.getTotalSales() == null ? saleAmount : item.getTotalSales().add(saleAmount));
            item.setLastUpdated(LocalDateTime.now());

            Inventory saved = inventoryRepository.save(item);
            updatedItems.add(saved);

            // snapshot AFTER + write audit row, same transaction
            auditRepository.save(InventoryStockAudit.builder()
                    .inventoryId(saved.getId())
                    .itemCode(saved.getItemCode())
                    .merchantId(saved.getMerchantId())
                    .actionType("SALE")
                    .availableStockBefore(availBefore)
                    .availableStockAfter(saved.getAvailableStock())
                    .soldStockBefore(soldBefore)
                    .soldStockAfter(saved.getSoldStock())
                    .totalSalesBefore(totalBefore)
                    .totalSalesAfter(saved.getTotalSales())
//                    .reference(request.getTransactionRef() != null ? request.getTransactionRef() : "N/A")
                    .performedBy(request.getMerchantId())
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        return updatedItems;
    }


    @Transactional
    public Inventory recordSale(Long id, int quantitySold, BigDecimal saleAmount) {
        Inventory item = getTodayRecordOrThrow(id);
        item.setSoldStock(item.getSoldStock() + quantitySold);
        item.computeAvailableStock();
        item.setTotalSales(item.getTotalSales().add(saleAmount));
        return inventoryRepository.save(item);
    }
    @Transactional
    public Inventory recordDeduction(Long id, BigDecimal amount) {
        Inventory item = getTodayRecordOrThrow(id);
        item.setDeductions(item.getDeductions().add(amount));
        return inventoryRepository.save(item);
    }
//
//@Transactional
//public List<Inventory> addMultipleStock(StockRequest request, String realMerchantId) {
//    List<Inventory> updatedItems = new ArrayList<>();
//
//    for (StockItemRequest itemReq : request.getItems()) {
//        if (itemReq.getQuantity() <= 0) {
//            throw new IllegalArgumentException(
//                    "Invalid quantity for item ID " + itemReq.getInventoryId() + ". Must be greater than zero."
//            );
//        }
//
//        Inventory item = inventoryRepository.findById(itemReq.getInventoryId())
//                .orElseThrow(() -> new RuntimeException("Item not found"));
//
//        // Security check: use the REAL merchantId we resolved in controller
//        if (!realMerchantId.equals(item.getMerchantId())) {
//            throw new IllegalArgumentException(
//                    "Item " + item.getItemName() + " does not belong to the authenticated merchant"
//            );
//        }
//
//        LocalDate today = LocalDate.now();
//
//        // If the item's record date is not today, reset it and carry forward stock
//        if (!today.equals(item.getRecordDate())) {
//            item.setRecordDate(today);
//            item.setStartingStock(item.getAvailableStock());
//            item.setSoldStock(0);
//            item.setAddedStock(0);
//            item.setDeductions(BigDecimal.ZERO);
//            item.setTotalSales(BigDecimal.ZERO);
//            item.setGrossSales(BigDecimal.ZERO);
//            item.setNetlSales(BigDecimal.ZERO);
//        }
//
//        // Add the new stock quantity
//        int currentAdded = item.getAddedStock() == null ? 0 : item.getAddedStock();
//        item.setAddedStock(currentAdded + itemReq.getQuantity());
//
//        // Recalculate available stock
//        item.computeAvailableStock();
//
//        updatedItems.add(inventoryRepository.save(item));
//    }
//
//    return updatedItems;
//}
@Transactional
public List<Inventory> addMultipleStock(StockRequest request, String realMerchantId) {
    List<Inventory> updatedItems = new ArrayList<>();

    for (StockItemRequest itemReq : request.getItems()) {
        if (itemReq.getQuantity() <= 0) {
            throw new IllegalArgumentException(
                    "Invalid quantity for item ID " + itemReq.getInventoryId() + ". Must be greater than zero."
            );
        }

        Inventory item = inventoryRepository.findById(itemReq.getInventoryId())
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (!realMerchantId.equals(item.getMerchantId())) {
            throw new IllegalArgumentException(
                    "Item " + item.getItemName() + " does not belong to the authenticated merchant"
            );
        }

        // snapshot BEFORE
        int availBefore = item.getAvailableStock();
        int addedBefore = item.getAddedStock() == null ? 0 : item.getAddedStock();

        LocalDate today = LocalDate.now();
        if (!today.equals(item.getRecordDate())) {
            item.setRecordDate(today);
            item.setStartingStock(item.getAvailableStock());
            item.setSoldStock(0);
            item.setAddedStock(0);
            item.setDeductions(BigDecimal.ZERO);
            item.setTotalSales(BigDecimal.ZERO);
            item.setGrossSales(BigDecimal.ZERO);
            item.setNetlSales(BigDecimal.ZERO);
        }

        int currentAdded = item.getAddedStock() == null ? 0 : item.getAddedStock();
        item.setAddedStock(currentAdded + itemReq.getQuantity());
        item.computeAvailableStock();
        item.setLastUpdated(LocalDateTime.now());

        Inventory saved = inventoryRepository.save(item);
        updatedItems.add(saved);

        // snapshot AFTER + audit row, same transaction
        auditRepository.save(InventoryStockAudit.builder()
                .inventoryId(saved.getId())
                .itemCode(saved.getItemCode())
                .merchantId(saved.getMerchantId())
                .actionType("RESTOCK")
                .availableStockBefore(availBefore)
                .availableStockAfter(saved.getAvailableStock())
                .addedStockBefore(addedBefore)
                .addedStockAfter(saved.getAddedStock())
                .reference(itemReq.getInventoryId().toString())
                .performedBy(realMerchantId)
                .createdAt(LocalDateTime.now())
                .build());
    }

    return updatedItems;
}


    public Map<String, BigDecimal> getDailySummary(String merchantId, LocalDate date) {

        Optional<DailySalesSummary> summaryOpt =
                dailySalesSummaryRepository.findByMerchantIdAndRecordDate(merchantId, date);

        BigDecimal grossSales = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal netSales = BigDecimal.ZERO;

        if (summaryOpt.isPresent()) {
            DailySalesSummary summary = summaryOpt.get();
            grossSales = summary.getGrossSales() != null ? summary.getGrossSales() : BigDecimal.ZERO;
            deductions = summary.getDeductions() != null ? summary.getDeductions() : BigDecimal.ZERO;
            netSales = summary.getNetSales() != null ? summary.getNetSales() : BigDecimal.ZERO;
        }

        Map<String, BigDecimal> result = new HashMap<>();
        result.put("grossSales", grossSales);
        result.put("deductions", deductions);
        result.put("netSales", netSales);

        return result;
    }


    private Inventory getTodayRecordOrThrow(Long id) {
        Inventory item = inventoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (item.getRecordDate() == null || !LocalDate.now().equals(item.getRecordDate())) {
            // create a new daily record from the previous one
            Inventory newRecord = new Inventory();
            newRecord.setMerchantId(item.getMerchantId());
            newRecord.setItemName(item.getItemName());
            newRecord.setItemCode(item.getItemCode());
            newRecord.setStartingStock(item.getAvailableStock());
            newRecord.setAddedStock(0);
            newRecord.setSoldStock(0);
            newRecord.setTotalSales(BigDecimal.ZERO);
            newRecord.setAvailableStock(item.getAvailableStock());
            newRecord.setUnitPrice(item.getUnitPrice());
            newRecord.setUnitCost(item.getUnitCost());
            newRecord.setRecordDate(LocalDate.now());

            return inventoryRepository.save(newRecord);
        }

        return item;
    }

    public Inventory getInventoryByMerchantIdAndItemCode(String merchantId, String itemCode) {
        return inventoryRepository.findByMerchantIdAndItemCode(merchantId, itemCode)
                .orElse(null);
    }


@Transactional
public Map<String, Object> closeDay(String merchantId) {
    logger.info("=============================================== closing day service BEGIN ============================================");

    // Validate input
    if (merchantId == null || merchantId.trim().isEmpty()) {
        throw new IllegalArgumentException("merchantId cannot be null or empty");
    }

    LocalDate today = LocalDate.now();

    // Check if already closed
    Optional<DailySalesSummary> existingSummary =
            dailySalesSummaryRepository.findByMerchantIdAndRecordDate(merchantId, today);

    if (existingSummary.isPresent() && Boolean.TRUE.equals(existingSummary.get().getIsClosed())) {
        logger.info("Day already closed for merchant {} on {}", merchantId, today);
        Map<String, Object> report = new HashMap<>();
        report.put("status", "ALREADY_CLOSED");
        report.put("statusCode", 400);
        report.put("message", "Day already closed for " + today);
        return report;
    }

    // Get only items that have a row for today
    List<Inventory> todayItems = inventoryRepository.findByMerchantIdAndRecordDate(merchantId, today);

    if (todayItems.isEmpty()) {
        throw new IllegalStateException("No inventory records found for merchant " + merchantId + " on " + today);
    }

    BigDecimal grossSales = BigDecimal.ZERO;
    BigDecimal totalDeductions = BigDecimal.ZERO;
    List<String> warnings = new ArrayList<>();

    for (Inventory item : todayItems) {
        logger.info("Fetched item");
        logger.info("item code: {}  Item name: {}", item.getItemCode(), item.getItemName());



        // Accumulate totals
        grossSales = grossSales.add(item.getTotalSales() != null ? item.getTotalSales() : BigDecimal.ZERO);
        totalDeductions = totalDeductions.add(item.getDeductions() != null ? item.getDeductions() : BigDecimal.ZERO);

        logger.info("saving item");
        logger.info("item code: {}  Item name: {}", item.getItemCode(), item.getItemName());
    }

//    inventoryRepository.saveAll(todayItems);

    BigDecimal netSales = grossSales.subtract(totalDeductions);

    // Update daily summary (your source of truth)
    DailySalesSummary summary = existingSummary.orElseGet(() -> DailySalesSummary.builder()
            .merchantId(merchantId)
            .recordDate(today)
            .grossSales(BigDecimal.ZERO)
            .deductions(BigDecimal.ZERO)
            .netSales(BigDecimal.ZERO)
            .createdAt(LocalDateTime.now())
            .build());

    summary.setGrossSales(grossSales);
    summary.setDeductions(totalDeductions);
    summary.setNetSales(netSales);
    summary.setIsClosed(true);  // Mark day as closed
    summary.setUpdatedAt(LocalDateTime.now());

    dailySalesSummaryRepository.save(summary);

    Map<String, Object> report = new HashMap<>();
    report.put("date", today);
    report.put("grossSales", grossSales);
    report.put("deductions", totalDeductions);
    report.put("netSales", netSales);
    report.put("itemsProcessed", todayItems.size());
    report.put("message", "Day closed successfully");
    report.put("status", "SUCCESS");
    report.put("statusCode", 200);

    if (!warnings.isEmpty()) {
        report.put("warnings", warnings);
        report.put("hasDataIssues", true);
    }

    logger.info("=============================================== closing day service END ============================================");

    return report;
}

    // Helper method for safely extracting numeric values
    private double getNumericValue(Row row, int cellIndex) {
        try {
            Cell cell = row.getCell(cellIndex);
            if (cell == null) return 0.0;
            if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
            if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isEmpty())
                return Double.parseDouble(cell.getStringCellValue());
        } catch (Exception ignored) {}
        return 0.0;
    }


    public Inventory addItem(Inventory item) {
        if (item.getItemCode() == null || item.getItemCode().isEmpty()) {
            String normalizedName = item.getItemName().replaceAll("\\s+", "").toUpperCase();
            item.setItemCode(item.getMerchantId() + "-" + normalizedName);
        }

        item.setAvailableStock(item.getStartingStock());

        item.computeAvailableStock();
        item.computeTotalSales();

        return inventoryRepository.save(item);
    }

    public Inventory addOrUpdateItem(Inventory item) {
        if (item.getItemCode() == null || item.getItemCode().isEmpty()) {
            String normalizedName = item.getItemName().replaceAll("\\s+", "").toUpperCase();
            item.setItemCode(item.getMerchantId() + "-" + normalizedName);
        }

        // Check if item already exists
        Optional<Inventory> existing = inventoryRepository.findByMerchantIdAndItemCode(item.getMerchantId(), item.getItemCode());

        if (existing.isPresent()) {
            Inventory existingItem = existing.get();
            existingItem.setAvailableStock(existingItem.getAvailableStock() + item.getAddedStock());
            existingItem.setUnitPrice(item.getUnitPrice());
            existingItem.setUnitCost(item.getUnitCost());
            existingItem.setRecordDate(LocalDate.now());
            existingItem.computeTotalSales();
            return inventoryRepository.save(existingItem);
        }

        // If not found, create new
        item.setAvailableStock(item.getStartingStock());
        item.computeTotalSales();
        return inventoryRepository.save(item);
    }
//    public List<WholesaleCatalogDto> getWholesaleCatalog(String merchantId) {
//        List<Inventory> items = inventoryRepository.findActiveByMerchantId(merchantId);
//
//        return items.stream()
//                .filter(item -> item.getAvailableStock() != null && item.getAvailableStock() > 0)
//                .map(item -> {
//                    Optional<WholesalePriceConfig> config = wholesalePriceConfigRepository
//                            .findLatestPrice(item.getItemCode(), merchantId);
//                    return config.map(c -> toWholesaleCatalogDto(item, c));
//                })
//                .filter(Optional::isPresent)
//                .map(Optional::get)
//                .toList();
//    }

    public List<WholesaleCatalogDto> getWholesaleCatalog(String merchantId) {
        List<Inventory> items = inventoryRepository.findByMerchantId(merchantId);

        return items.stream()
                .map(item -> {
                    WholesalePriceConfig config = wholesalePriceConfigRepository
                            .findLatestPrice(item.getItemCode(), merchantId)
                            .orElse(null);
                    return toWholesaleCatalogDto(item, config); // config may be null
                })
                .toList();
    }

    private WholesaleCatalogDto toWholesaleCatalogDto(Inventory item, WholesalePriceConfig config) {
        WholesaleCatalogDto dto = new WholesaleCatalogDto();
        dto.setInventoryId(item.getId());
        dto.setItemName(item.getItemName());
        dto.setItemCode(item.getItemCode());
        dto.setWholesalePrice(config.getWholesalePrice());
        dto.setAvailability(resolveAvailability(item.getAvailableStock())); // ← replaces availableStock
        dto.setProductImageUrl(item.getProductImageUrl());
        dto.setProductCategory(item.getProductCategory());
        dto.setProductBrand(item.getProductBrand());
        return dto;
    }

    private String resolveAvailability(Integer stock) {
        if (stock == null || stock <= 0) return "OUT_OF_STOCK";
        if (stock <= 10) return "LOW_STOCK";
        return "IN_STOCK";
    }

    @Transactional
    public RecurringExpense createRecurringExpense(String merchantId, RecurringExpenseRequest request) {

        RecurringExpense recurring = RecurringExpense.builder()
                .merchantId(merchantId)
                .amount(request.getAmount())
                .narration(request.getNarration())
                .frequency(request.getFrequency())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .nextExecutionDate(request.getStartDate())
                .isActive(true)
                .build();

        return recurringExpenseRepository.save(recurring);
    }
    @Scheduled(cron = "0 0 1 * * *")   // Runs at 1:00 AM every day
    @Transactional
    public void processDueRecurringExpenses() {
        log.info("Starting processing of due recurring expenses...");

        LocalDate today = LocalDate.now();

        List<RecurringExpense> dueExpenses = recurringExpenseRepository
                .findDueRecurringExpenses(today);

        int processed = 0;

        for (RecurringExpense re : dueExpenses) {
            try {
                // Reuse existing logic - This is the key point you wanted
                recordExpense(re.getMerchantId(), re.getAmount(), re.getNarration());

                // Update next execution date
                re.updateNextExecutionDate();
                recurringExpenseRepository.save(re);

                processed++;
                log.info("Processed recurring expense ID: {} for merchant: {}",
                        re.getId(), re.getMerchantId());

            } catch (Exception e) {
                log.error("Failed to process recurring expense ID: {}", re.getId(), e);
            }
        }

        log.info("Completed recurring expenses processing. {} expenses executed.", processed);
    }

@Transactional
public void recordExpense(String merchantId, BigDecimal amount, String narration) {
    LocalDate today = LocalDate.now();

    // Record individual expense
    Expense expense = Expense.builder()
            .merchantId(merchantId)
            .expenseDate(today)
            .amount(amount)
            .narration(narration)
            .build();
    expenseRepository.save(expense);

    // Get or create the daily summary (source of truth)
    DailySalesSummary summary = dailySalesSummaryRepository
            .findByMerchantIdAndRecordDate(merchantId, today)
            .orElse(DailySalesSummary.builder()
                    .merchantId(merchantId)
                    .recordDate(today)
                    .grossSales(BigDecimal.ZERO)
                    .deductions(BigDecimal.ZERO)
                    .netSales(BigDecimal.ZERO)
                    .createdAt(LocalDateTime.now())
                    .build());

    // Add the expense to deductions
    BigDecimal currentDeductions = summary.getDeductions() != null ? summary.getDeductions() : BigDecimal.ZERO;
    summary.setDeductions(currentDeductions.add(amount));

    // Recalculate net sales (gross - deductions)
    BigDecimal gross = summary.getGrossSales() != null ? summary.getGrossSales() : BigDecimal.ZERO;
    summary.setNetSales(gross.subtract(summary.getDeductions()));

    summary.setUpdatedAt(LocalDateTime.now());
    dailySalesSummaryRepository.save(summary);

    // Optional: if you still want to update inventory row(s) – but since expenses are general,
    // this might not make sense per item. If expenses are per item, add itemCode to Expense and filter.
    // For now, skipping or keeping as is (but note: current findFirst is problematic if multiple items)
    Optional<Inventory> inventoryOpt = inventoryRepository
            .findFirstByMerchantIdAndRecordDate(merchantId, today);

    if (inventoryOpt.isPresent()) {
        Inventory item = inventoryOpt.get();
        BigDecimal currentExpense = item.getDeductions() != null ? item.getDeductions() : BigDecimal.ZERO;
        item.setDeductions(currentExpense.add(amount));
        // Note: no more single expenseNote – use the new Expense table for details
        inventoryRepository.save(item);
    }
}



    public List<Inventory> getAllItems() {
        return inventoryRepository.findAll();
    }
    public List<Inventory> getAllItemsForMerchant(String merchantId) {
        System.out.println("Getting inventory for merchantId: " + merchantId);
        return inventoryRepository.findByMerchantId(merchantId);
    }


    public List<InventoryResponseDto> getAllItemsForMerchantWithPricing(String merchantId, User user) {

        List<Inventory> items = inventoryRepository.findByMerchantId(merchantId);

        return items.stream()
                .map(item -> convertToResponseDto(item, user))
                .toList();
    }

    private InventoryResponseDto convertToResponseDto(Inventory item, User user) {
        BigDecimal displayPrice = item.getUnitPrice();   // default = retail price
        String priceType = "RETAIL";

        // Check if merchant has Wholesale subscription
        if (isWholesaleSubscriber(user)) {
            displayPrice = calculateWholesalePrice(item);
            priceType = "WHOLESALE";
        }

        InventoryResponseDto dto = new InventoryResponseDto();

        dto.setId(item.getId());
        dto.setMerchantId(item.getMerchantId());
        dto.setItemName(item.getItemName());
        dto.setItemCode(item.getItemCode());

        dto.setStartingStock(item.getStartingStock());
        dto.setAddedStock(item.getAddedStock());
        dto.setSoldStock(item.getSoldStock());
        dto.setAvailableStock(item.getAvailableStock());
        dto.setClosingStock(item.getClosingStock());

        dto.setTotalSales(item.getTotalSales());
        dto.setGrossSales(item.getGrossSales());
        dto.setNetlSales(item.getNetlSales());
        dto.setDeductions(item.getDeductions());

        dto.setUnitCost(item.getUnitCost());
        dto.setUnitPrice(displayPrice);           // ← Dynamic price applied here

        dto.setExpenseNote(item.getExpenseNote());
        dto.setIsActive(item.getIsActive());

        dto.setProductImageUrl(item.getProductImageUrl());
        dto.setProductDescription(item.getProductDescription());
        dto.setProductCategory(item.getProductCategory());
        dto.setBatchNumber(item.getBatchNumber());
        dto.setSupplierName(item.getSupplierName());
        dto.setDatePurchased(item.getDatePurchased());
        dto.setExpiryDate(item.getExpiryDate());
        dto.setReorderLevel(item.getReorderLevel());
        dto.setLastUpdated(item.getLastUpdated());
        dto.setProductBrand(item.getProductBrand());
        dto.setRecordDate(item.getRecordDate());

        dto.setWholesalePrice(calculateWholesalePrice(item));  // always return for reference
        dto.setPriceType(priceType);

        return dto;
    }


    public BigDecimal calculateNetSales(String merchantId) {
        List<Inventory> items = inventoryRepository.findByMerchantId(merchantId);

        return items.stream()
                .map(i -> (i.getTotalSales() == null ? BigDecimal.ZERO : i.getTotalSales())
                        .subtract(i.getDeductions() == null ? BigDecimal.ZERO : i.getDeductions()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isWholesaleSubscriber(User user) {
        if (user == null || user.getSubscriptionPlan() == null) {
            return false;
        }

        String plan = user.getSubscriptionPlan().toLowerCase().trim();
        return plan.contains("wholesale") ||
                plan.contains("business") ||
                plan.contains("premium") ||
                plan.contains("enterprise");
    }

    /**
     * Calculate Wholesale Price Logic - Customize this as needed
     */


    private BigDecimal calculateWholesalePrice(Inventory item) {
        // 1. Try merchant-specific price
        Optional<WholesalePriceConfig> config = wholesalePriceConfigRepository
                .findActivePrice(item.getItemCode(), item.getMerchantId(), LocalDate.now());

        if (config.isPresent()) {
            return config.get().getWholesalePrice();
        }

        // 2. Try global fallback price (merchantId = "GLOBAL")
        Optional<WholesalePriceConfig> globalConfig = wholesalePriceConfigRepository
                .findActivePrice(item.getItemCode(), "GLOBAL", LocalDate.now());

        if (globalConfig.isPresent()) {
            return globalConfig.get().getWholesalePrice();
        }

        // 3. Last resort: fall back to computed price if no config exists yet
        BigDecimal cost = item.getUnitCost();
        if (cost != null && cost.compareTo(BigDecimal.ZERO) > 0) {
            return cost.multiply(BigDecimal.valueOf(1.20));
        }

        return item.getUnitPrice() != null
                ? item.getUnitPrice().multiply(BigDecimal.valueOf(0.85))
                : BigDecimal.ZERO;
    }
//    private BigDecimal calculateWholesalePrice(Inventory item) {
//        BigDecimal cost = item.getUnitCost();
//        BigDecimal unitPrice = item.getUnitPrice();
//
//        boolean hasCost = cost != null && cost.compareTo(BigDecimal.ZERO) > 0;
//        boolean hasUnitPrice = unitPrice != null && unitPrice.compareTo(BigDecimal.ZERO) > 0;
//
//        if (hasCost) {
//            // Cost-based: Cost + 20% margin
//            return cost.multiply(BigDecimal.valueOf(1.20));
//        }
//
//        if (hasUnitPrice) {
//            // Fallback: 85% of retail price
//            return unitPrice.multiply(BigDecimal.valueOf(0.85));
//        }
//
//        return BigDecimal.ZERO; // no basis for calculation
//    }

    @Transactional
    public Inventory updateInventoryItem(Long id, String merchantId, String itemName, Integer quantity, BigDecimal unitPrice) {
        Inventory item = inventoryRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found or access denied"));

        if (itemName != null && !itemName.isBlank()) {
            item.setItemName(itemName.trim());
        }

        if (quantity != null) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantity cannot be negative");
            }
            item.setAvailableStock(quantity);
            // DO NOT call computeAvailableStock() here — it would override your direct set!
            // If you want to keep starting/added/sold in sync, set them manually (e.g. reset soldStock=0)
            // For now, direct set is fine if this is a manual adjustment
        } else {
            // Only recompute if no direct quantity provided
            item.computeAvailableStock();
        }

        if (unitPrice != null) {
            if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Unit price cannot be negative");
            }
            item.setUnitPrice(unitPrice);
            item.computeTotalSales();  // This recalcs based on unitPrice * soldStock
        }

        // Optional: Update closingStock to match availableStock
        item.setClosingStock(item.getAvailableStock());

        return inventoryRepository.save(item);
    }

    @Transactional
    public void hardDeleteInventoryItem(Long id, String merchantId) {
        Inventory item = inventoryRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inventory item not found or you do not have permission to delete it (ID: " + id + ")"
                ));

        // Optional: Check if item has sales history before hard delete
        if (item.getSoldStock() != null && item.getSoldStock() > 0) {
            throw new IllegalArgumentException("Cannot permanently delete item with sales history");
        }

        inventoryRepository.deleteById(id);
    }

    private double parseDouble(String value) {
        try {
            return value.isEmpty() ? 0.0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int parseInt(String value) {
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String getStringCellValue(Row row, int cellIndex) {
        Cell cell = row.getCell(cellIndex);
        if (cell == null) return "";
        cell.setCellType(CellType.STRING);
        return cell.getStringCellValue().trim();
    }

    public TransactionReconciliationDto getReconciliation(String merchantId, LocalDate date) {
        List<SaleLineProjection> lines = inventoryRepository.getReconciliationLines(merchantId, date);

        Map<String, List<SaleLineProjection>> grouped = lines.stream()
                .collect(Collectors.groupingBy(
                        SaleLineProjection::getTransactionRef,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<BasketDto> baskets = grouped.entrySet().stream()
                .map(entry -> {
                    List<SaleLineProjection> basketLines = entry.getValue();
                    BasketDto basket = new BasketDto();

                    basket.setTransactionRef(entry.getKey());
                    basket.setSaleDatetime(basketLines.get(0).getSaleDatetime());
                    // basket.setCustomerPhone(basketLines.get(0).getCustomerPhone());
                    basket.setMerchantName(basketLines.get(0).getMerchantName());
                    basket.setMerchantPhone(basketLines.get(0).getMerchantPhone());

                    basket.setItemCount(basketLines.size());
                    basket.setTotalUnits(basketLines.stream()
                            .mapToInt(SaleLineProjection::getQuantity)
                            .sum());
                    basket.setBasketTotal(basketLines.stream()
                            .map(SaleLineProjection::getTotalPrice)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));

                    basket.setItems(basketLines.stream()
                            .map(this::toBasketLine)
                            .toList());

                    return basket;
                })
                .sorted(Comparator.comparing(BasketDto::getSaleDatetime))
                .toList();

        TransactionReconciliationDto report = new TransactionReconciliationDto();
        report.setDate(date);
        report.setMerchantId(merchantId);
        report.setTotalTransactions(baskets.size());
        report.setTotalUnits(baskets.stream()
                .mapToInt(BasketDto::getTotalUnits)
                .sum());
        report.setTotalRevenue(baskets.stream()
                .map(BasketDto::getBasketTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setTransactions(baskets);

        return report;
    }

//    public TransactionReconciliationDto getReconciliation(String merchantId, LocalDate date) {
//        List<SaleLineProjection> lines = inventoryRepository.getReconciliationLines(merchantId, date);
//
//        Map<String, List<SaleLineProjection>> grouped = lines.stream()
//                .collect(Collectors.groupingBy(SaleLineProjection::getTransactionRef, LinkedHashMap::new, Collectors.toList()));
//
//        List<BasketDto> baskets = grouped.entrySet().stream()
//                .map(entry -> {
//                    List<SaleLineProjection> basketLines = entry.getValue();
//                    BasketDto basket = new BasketDto();
//                    basket.setTransactionRef(entry.getKey());
//                    basket.setSaleDatetime(basketLines.get(0).getSaleDatetime());
////                    basket.setCustomerPhone(basketLines.get(0).getCustomerPhone());
//                    basket.setMerchantName(basketLines.get(0).getMerchantName());
//                    basket.setMerchantPhone(basketLines.get(0).getMerchantPhone());
//                    basket.setItemCount(basketLines.size());
//                    basket.setTotalUnits(basketLines.stream().mapToInt(SaleLineProjection::getQuantity).sum());
//                    basket.setBasketTotal(basketLines.stream()
//                            .map(SaleLineProjection::getTotalPrice)
//                            .reduce(BigDecimal.ZERO, BigDecimal::add));
//                    basket.setItems(basketLines.stream().map(this::toBasketLine).toList());
//                    return basket;
//                })
//                .sorted(Comparator.comparing(BasketDto::getSaleDatetime))
//                .toList();
//
//        TransactionReconciliationDto report = new TransactionReconciliationDto();
//        report.setDate(date);
//        report.setMerchantId(merchantId);
//        report.setTotalTransactions(baskets.size());
//        report.setTotalUnits(baskets.stream().mapToInt(BasketDto::getTotalUnits).sum());
//        report.setTotalRevenue(baskets.stream().map(BasketDto::getBasketTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
//        report.setTransactions(baskets);
//        return report;
//    }

    private BasketLineDto toBasketLine(SaleLineProjection p) {
        BasketLineDto dto = new BasketLineDto();
        dto.setItemCode(p.getItemCode());
        dto.setItemName(p.getItemName());
        dto.setQuantity(p.getQuantity());
        dto.setUnitPrice(p.getUnitPrice());
        dto.setTotalPrice(p.getTotalPrice());
        dto.setOrderType(p.getOrderType());
        return dto;
    }





}
