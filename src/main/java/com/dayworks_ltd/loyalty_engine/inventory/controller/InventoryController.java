package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.enums.BusinessType;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.auth.services.CustomUserDetailsService;
import com.dayworks_ltd.loyalty_engine.auth.services.JWTService;
import com.dayworks_ltd.loyalty_engine.campaign.service.Campaign;
import com.dayworks_ltd.loyalty_engine.campaigns.CampaignService;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.*;
import com.dayworks_ltd.loyalty_engine.inventory.models.DefaultProduct;
import com.dayworks_ltd.loyalty_engine.inventory.models.DailySalesSummary;
import com.dayworks_ltd.loyalty_engine.inventory.models.Expense;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.SaleTransaction;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.DailySalesSummaryRepository;

import com.dayworks_ltd.loyalty_engine.inventory.repositories.ExpenseRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.DefaultProductRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.SaleTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.InventoryService;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.payments.PaymentService;
import com.dayworks_ltd.loyalty_engine.payments.models.PaymentNotification;
import com.dayworks_ltd.loyalty_engine.utility.LoyaltyUtil;
import com.dayworks_ltd.loyalty_engine.utility.Pair;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Inventory Controller
 * Handles REST endpoints for managing stock, sales, and expenses
 * Works for restaurants, agrovets, or any merchant on the Loyalty Engine
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name ="Inventory API", description = "defines endpoints for inventory manipulation")
public class InventoryController {

    private final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private DailySalesSummaryRepository dailySalesSummaryRepository;
    @Autowired
    private SaleTransactionRepository saleTransactionRepository;
    private final ExpenseRepository expenseRepository;
    private final DefaultProductRepository defaultProductRepository;


    @Autowired
    private JWTService jwtService;
    @Autowired
    private CustomUserDetailsService customUserDetailsService;




    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Campaign campaign;

    @Autowired
    private MerchantService merchantService;

    public InventoryController(ExpenseRepository expenseRepository, DefaultProductRepository defaultProductRepository) {
        this.expenseRepository = expenseRepository;
        this.defaultProductRepository = defaultProductRepository;
    }

    @GetMapping("/product-defaults")
    public ResponseEntity<?> getAllDefaultProducts(
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {

        Logger logger = LoggerFactory.getLogger(getClass());

        try {
            if (customUserDetails == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 401,
                        "message", "Authentication required"
                ));
            }

            Long userId = customUserDetails.getUserId();
            User user = userRepository.getUserById(userId);

            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 401,
                        "message", "User not found"
                ));
            }

            Merchant merchant = user.getMerchant();

            if (merchant == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            BusinessType businessType = BusinessType.valueOf(merchant.getBusinessType());

            List<DefaultProduct> defaultProducts =
                    defaultProductRepository.findByBusinessType(businessType);

            List<DefaultProductDto> result = defaultProducts.stream()
                    .map(p -> DefaultProductDto.builder()
                            .productName(p.getProductName())
                            .productCode(p.getProductCode())
                            .volumeMl(p.getVolume_Ml())
                            .build())
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "statusCode", 200,
                    "message", "Default products retrieved successfully",
                    "data", result,
                    "count", result.size()
            ));

        } catch (Exception e) {
            logger.error("Error fetching default products", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve default products: " + e.getMessage()
            ));
        }
    }


    @GetMapping("/all")
    @Operation(summary = "Get All Inventory Items with dynamic pricing")
    public ResponseEntity<?> getAllItems(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam String merchantId) {

        try {
            if (customUserDetails == null) {
                return ResponseEntity.status(401).body(Map.of("status", "FAILURE", "message", "Unauthorized"));
            }

            Long userId = customUserDetails.getUserId();
            User user = userRepository.getUserById(userId);

            if (user == null || user.getMerchantId() == null || user.getMerchantId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "User is not linked to any merchant"
                ));
            }

            String realMerchantId = user.getMerchantId();

            // Use new method that handles wholesale pricing
            List<InventoryResponseDto> items = inventoryService
                    .getAllItemsForMerchantWithPricing(realMerchantId,user);

            return ResponseEntity.ok(items);

        } catch (Exception e) {
            logger.error("Error fetching inventory with pricing", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve inventory"
            ));
        }
    }
    @PostMapping("/batch-add-defaults")
    @Operation(summary = "Batch add selected default products to inventory",
            description = "Creates new inventory items from selected default products. "
                    + "merchantId in body is actually the USER ID — real merchant is resolved from DB.")
    public ResponseEntity<Map<String, Object>> batchAddDefaultProducts(
            @RequestBody BatchAddDefaultsRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String userIdStr = request.getMerchantId();
            if (userIdStr == null || userIdStr.trim().isEmpty()) {
                response.put("status", "FAILURE");
                response.put("statusCode", 400);
                response.put("message", "merchantId (user ID) is required");
                return ResponseEntity.badRequest().body(response);
            }

            Long userId;
            try {
                userId = Long.parseLong(userIdStr.trim());
            } catch (NumberFormatException e) {
                response.put("status", "FAILURE");
                response.put("statusCode", 400);
                response.put("message", "Invalid user ID format");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                response.put("status", "FAILURE");
                response.put("statusCode", 400);
                response.put("message", "No user with specified ID exists");
                return ResponseEntity.badRequest().body(response);
            }

            String realMerchantId = userOpt.get().getMerchantId();
            if (realMerchantId == null || realMerchantId.trim().isEmpty()) {
                response.put("status", "FAILURE");
                response.put("statusCode", 400);
                response.put("message", "This user is not linked to any merchant");
                return ResponseEntity.badRequest().body(response);
            }

            // Logging for debugging (same style as your other endpoints)
            System.out.println("BATCH-ADD-DEFAULTS - Incoming userId: " + userIdStr +
                    " → Resolved to real merchantId: " + realMerchantId);

            // Process the list
            BatchAddResult result = inventoryService.batchAddFromDefaults(
                    request.getSelections(), realMerchantId);

            response.put("status", "SUCCESS");
            response.put("statusCode", 200);
            response.put("message", String.format("Batch add completed: %d added, %d skipped (already exist)",
                    result.getAddedCount(), result.getSkippedCount()));
            response.put("addedCount", result.getAddedCount());
            response.put("skippedCount", result.getSkippedCount());
            response.put("totalProcessed", request.getSelections().size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("status", "ERROR");
            response.put("statusCode", 500);
            response.put("message", "Failed to batch add default products: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }



    @PostMapping("/import")
    @Operation(summary = "Upload Inventory via CSV", description = "Update Inventory via CSV list of items")
    public ResponseEntity<Map<String, Object>> importInventory(
            @RequestParam("file") MultipartFile file,
            @RequestParam("merchantId") String merchantId) {  // ← This is actually the USER ID

        Map<String, Object> response = new HashMap<>();

        try {
            // === SAME PATTERN AS ALL OTHER ENDPOINTS ===
            if (merchantId == null || merchantId.isBlank()) {
                response.put("statusCode", 400);
                response.put("message", "merchantId is required");
                return ResponseEntity.badRequest().body(response);
            }

            Long userId;
            try {
                userId = Long.parseLong(merchantId);
            } catch (NumberFormatException e) {
                response.put("statusCode", 400);
                response.put("message", "Invalid user ID format");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                response.put("statusCode", 400);
                response.put("message", "No user with specified ID exists");
                return ResponseEntity.badRequest().body(response);
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                response.put("statusCode", 400);
                response.put("message", "This user is not linked to any merchant");
                return ResponseEntity.badRequest().body(response);
            }

            // Optional log
            System.out.println("IMPORT INVENTORY - Incoming userId: " + merchantId +
                    " → Resolved to real merchantId: " + realMerchantId);

            // === NOW IMPORT USING THE REAL MERCHANT ID ===
            inventoryService.importFromExcel(file, realMerchantId);

            response.put("statusCode", 200);
            response.put("message", "Inventory successfully imported for merchant " + realMerchantId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("statusCode", 500);
            response.put("message", "Failed to import inventory: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }




    @PutMapping("/{id}/deduction")
    @Operation(summary = "Record deduction", description = "record deductions made")
    public ResponseEntity<Inventory> recordDeduction(
            @PathVariable Long id,
            @RequestParam BigDecimal amount
    ) {
        return ResponseEntity.ok(inventoryService.recordDeduction(id, amount));
    }



    @PostMapping("/add-stock")
    @Operation(summary = "Update available stock", description = "update the available stock for a particular inventory item")
    public ResponseEntity<?> addStock(@RequestBody StockRequest request) {
        try {
            // === SAME PATTERN AS ALL OTHER ENDPOINTS ===
            String userIdStr = request.getMerchantId();  // This is the USER ID from UI
            if (userIdStr == null || userIdStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "merchantId is required"
                ));
            }

            Long userId = Long.parseLong(userIdStr);
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "This user is not linked to any merchant"
                ));
            }

            // Optional log
            System.out.println("ADD-STOCK - Incoming userId: " + userIdStr +
                    " → Resolved to real merchantId: " + realMerchantId);

            // === NOW ADD STOCK USING THE REAL MERCHANT ID ===
            // You need to pass the realMerchantId to your service method
            // Assuming your StockRequest has a list of items with inventoryId and quantity
            List<Inventory> updatedItems = inventoryService.addMultipleStock(request, realMerchantId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Stock added successfully",
                    "data", updatedItems
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", "Invalid user ID format"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to add stock",
                    "error", e.getMessage()
            ));
        }
    }


    @GetMapping("/daily-summary/{merchantId}")
    @Operation(summary = "Fetch daily summary", description = "Fetch the daily summary for the specified merchant")
    public ResponseEntity<Map<String, Object>> dailySummary(
            @PathVariable String merchantId,   // ← This is actually the USER ID sent from UI
            @RequestParam(required = false) String date) {

        try {
            // === RESOLVE REAL MERCHANT ID - SAME PATTERN AS /close-day AND /sale ===
            Long userId = Long.parseLong(merchantId);  // "15" → userId

            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            // Optional log
            System.out.println("DAILY-SUMMARY - Incoming userId: " + merchantId +
                    " → Resolved to real merchantId: " + realMerchantId);

            // Parse date
            LocalDate d = (date == null || date.isBlank())
                    ? LocalDate.now()
                    : LocalDate.parse(date);

            // Fetch summary using the REAL merchantId
            Map<String, BigDecimal> summaryData = inventoryService.getDailySummary(realMerchantId, d);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "SUCCESS");
            response.put("statusCode", 200);
            response.put("message", "Daily summary retrieved successfully for merchant " + realMerchantId);
            response.put("data", summaryData);

            return ResponseEntity.ok(response);

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "Invalid user ID format"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve daily summary: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/initiate-close-day")
    @Operation(summary = "Initiate close day", description = "Initiate close day. This sends an OTP to the registered phone of the merchant")
    public ResponseEntity<Map<String, Object>> initiateCloseDay(@RequestBody Map<String, String> request) {
        try {
            String userId = request.get("merchantId");
            Optional<User> user = userRepository.findById(Long.parseLong(userId));

            if( user.isEmpty() )
                throw new Exception("No user with specified ID exists!");
            String merchantId = user.get().getMerchantId();

            if(merchantId == null)
            {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "statusCode", 400,
                        "message", "invalid merchantId details"
                ));
            }
            else
            {
                Optional<Merchant> merchantOptional = merchantService.getMerchantById(Long.parseLong(merchantId));

                if(merchantOptional.isPresent())
                {
                    String generatedOtp = LoyaltyUtil.generateFourDigitOtp();
                    merchantService.updateMerchantOtp(merchantId, generatedOtp);
                    ArrayList<Pair<String, String>> list = new ArrayList<>();
                    list.add( new Pair<String, String>(merchantOptional.get().getBusinessPhone(), generatedOtp));
                    boolean messageSent = campaign.sendSMSMessage(list);
                    return ResponseEntity.ok(Map.of(
                            "status", messageSent ? "SUCCESS" : "FAILED",
                            "statusCode", messageSent ? 200 : 400,
                            "message", messageSent ? "Successfully sent otp to merchant with id: " + merchantId : "could not send OTP to merchant with id: " + merchantId
                    ));
                }
                else
                {
                    return ResponseEntity.badRequest().body(Map.of(
                            "status", "FAILED",
                            "statusCode", 400,
                            "message", "merchant with id: " + merchantId + " does not exist"
                    ));
                }
            }

        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILED",
                    "statusCode", 400,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Unexpected error while closing day: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/close-day")
    @Operation(summary = "Finalize close business day", description = "Finalize close business day only if provided OTP matches the one sent to the merchant.")
    public ResponseEntity<Map<String, Object>> closeDay(@RequestBody Map<String, String> request) {
        try {
            String userId = request.get("merchantId");
            Optional<User> user = userRepository.findById(Long.parseLong(userId));

            if(user.isEmpty())
                throw new Exception("No user with specified ID exists!");

            String merchantId = user.get().getMerchantId();
            String username = user.get().getUsername();

            String closeDayOtp = request.get("otp");
            if(merchantId == null || closeDayOtp == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "statusCode", 400,
                        "message", "invalid close day details"
                ));
            }

            String generatedOtp = merchantService.getMerchantOtp(merchantId);

            if((generatedOtp == null || generatedOtp.isBlank()) || (closeDayOtp.isBlank())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "statusCode", 400,
                        "message", "null OTP code"
                ));
            }
            else if(!closeDayOtp.equals(generatedOtp)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "statusCode", 400,
                        "message", "invalid OTP code"
                ));
            }
            else {
                // CALL CLOSE DAY SERVICE
                Map<String, Object> summaryData = inventoryService.closeDay(merchantId);
                logger.info("Obtained merchant sales summary");
                logger.info("{}", summaryData);

                // CHECK IF CLOSE DAY WAS SUCCESSFUL
                int statusCode = (int) summaryData.get("statusCode");
                String status = (String) summaryData.get("status");

                if(statusCode == 200 && "SUCCESS".equals(status)) {
                    // Day closed successfully - send SMS with actual data
                    Optional<Merchant> merchantOptional = merchantService.getMerchantById(Long.parseLong(merchantId));
                    ArrayList<Pair<String, String>> list = new ArrayList<>();

                    list.add(new Pair<>(
                            merchantOptional.isPresent() ? merchantOptional.get().getBusinessPhone() : null,
                            new StringBuilder()
                                    .append("Hi ").append(username).append("! ")
                                    .append("Today's Sales Report: Gross Sales: ")
                                    .append(summaryData.get("grossSales"))
                                    .append(" Deductions: ")
                                    .append(summaryData.get("deductions"))
                                    .append(" Net Sales: ")
                                    .append(summaryData.get("netSales"))
                                    .toString()
                    ));

                    logger.info("Sending information on sales");
                    logger.info("{}", list);
                    campaign.sendSMSMessage(list);

                    // Clear OTP after successful close
                    merchantService.updateMerchantOtp(merchantId, "");

                    // Return the actual service response
                    return ResponseEntity.ok(summaryData);

                } else if(statusCode == 400 && "ALREADY_CLOSED".equals(status)) {
                    // Day already closed - don't send SMS, just return the response
                    logger.warn("Day already closed for merchant {}", merchantId);
                    // Don't clear OTP or send SMS
                    return ResponseEntity.badRequest().body(summaryData);

                } else {
                    // Other error from service
                    logger.error("Close day service returned error: {}", summaryData.get("message"));
                    return ResponseEntity.badRequest().body(summaryData);
                }
            }

        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILED",
                    "statusCode", 400,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Unexpected error while closing day: " + e.getMessage()
            ));
        }
    }


    @GetMapping("/weekly")
    @Operation(summary = "Fetch weekly summary", description = "Provide the merchant weekly sales summary")
    public ResponseEntity<?> getWeeklySummary(
            @RequestParam String merchantId,   // ← This is actually the USER ID from UI
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        try {
            // === RESOLVE REAL MERCHANT ID - SAME PATTERN AS EVERYWHERE ELSE ===
            Long userId = Long.parseLong(merchantId);

            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            // Optional log
            System.out.println("WEEKLY SUMMARY - Incoming userId: " + merchantId +
                    " → Resolved to real merchantId: " + realMerchantId);

            // Parse dates
            LocalDate endDate = end != null && !end.isBlank()
                    ? LocalDate.parse(end)
                    : LocalDate.now();

            LocalDate startDate = start != null && !start.isBlank()
                    ? LocalDate.parse(start)
                    : endDate.minusDays(6);

            // Fetch summaries using the REAL merchantId
            List<DailySalesSummary> summaries = dailySalesSummaryRepository
                    .findByMerchantIdAndRecordDateBetween(realMerchantId, startDate, endDate);

            BigDecimal gross = summaries.stream()
                    .map(DailySalesSummary::getGrossSales)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal net = summaries.stream()
                    .map(DailySalesSummary::getNetSales)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal deductions = summaries.stream()
                    .map(DailySalesSummary::getDeductions)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return ResponseEntity.ok(Map.of(
                    "statusCode", 200,
                    "status", "SUCCESS",
                    "message", "Weekly summary retrieved successfully",
                    "range", Map.of("start", startDate, "end", endDate),
                    "data", Map.of(
                            "grossSales", gross,
                            "deductions", deductions,
                            "netSales", net,
                            "dailyTrend", summaries
                    )
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "Invalid user ID format"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve weekly summary: " + e.getMessage()
            ));
        }
    }

// Updated /expense endpoint – minimal changes, but narration instead of note for clarity
    @PostMapping("/expense")
    @Operation(summary = "Record expense", description = "Record expense incurred by merchant with narration")
    public ResponseEntity<Map<String, Object>> recordExpense(@RequestBody Map<String, Object> request) {
        try {
            String userIdStr = (String) request.get("merchantId");  // This is the USER ID from UI
            if (userIdStr == null || userIdStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "merchantId is required"
                ));
            }

            Long userId = Long.parseLong(userIdStr);
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            // Optional log
            System.out.println("EXPENSE - Incoming userId: " + userIdStr +
                    " → Resolved to real merchantId: " + realMerchantId);

            // Extract amount and narration
            Object amountObj = request.get("amount");
            if (amountObj == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "amount is required"
                ));
            }

            BigDecimal amount = new BigDecimal(amountObj.toString());

            String narration = (String) request.get("narration");  // Changed from note
            if (narration == null) {
                narration = "";  // optional
            }

            // Record expense using the REAL merchantId
            inventoryService.recordExpense(realMerchantId, amount, narration);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "statusCode", 200,
                    "message", "Expense recorded successfully for merchant " + realMerchantId
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "Invalid user ID or amount format"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to record expense: " + e.getMessage()
            ));
        }
    }
    // New endpoint to pull expenses (add to your controller)
    @GetMapping("/expenses")
    @Operation(summary = "Get expenses for a specific day")
    public ResponseEntity<?> getDailyExpenses(
            @RequestParam String merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        try {
            Long userId = Long.parseLong(merchantId);
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();
            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            List<Expense> expenses = expenseRepository
                    .findByMerchantIdAndExpenseDateOrderByCreatedAtDesc(realMerchantId, date);

            List<Map<String, Object>> expenseList = expenses.stream()
                    .map(e -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", e.getId());
                        item.put("amount", e.getAmount());
                        item.put("narration", e.getNarration() != null ? e.getNarration() : "");
                        item.put("createdAt", e.getCreatedAt().toString());
                        return item;
                    })
                    .collect(Collectors.toList());

            BigDecimal totalDeductions = expenses.stream()
                    .map(Expense::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "date", date.toString(),
                    "merchantId", realMerchantId,
                    "totalDeductions", totalDeductions,
                    "numberOfExpenses", expenses.size(),
                    "expenses", expenseList
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "Invalid user ID format"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve expenses: " + e.getMessage()
            ));
        }
    }

@GetMapping("/report/{merchantId}")
@Operation(summary = "Get merchant report", description = "Get merchant business report")
public ResponseEntity<?> getMerchantReport(@PathVariable Long merchantId) {  // ← This is actually the USER ID

    try {
        // === SAME PATTERN AS ALL OTHER ENDPOINTS ===
        Optional<User> userOpt = userRepository.findById(merchantId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "No user with specified ID exists"
            ));
        }

        String realMerchantId = userOpt.get().getMerchantId();

        if (realMerchantId == null || realMerchantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "This user is not linked to any merchant"
            ));
        }

        // Optional log
        System.out.println("MERCHANT REPORT - Incoming userId: " + merchantId +
                " → Resolved to real merchantId: " + realMerchantId);

        // === NOW CALCULATE USING THE REAL MERCHANT ID ===
        BigDecimal netSales = inventoryService.calculateNetSales(realMerchantId);

        return ResponseEntity.ok(netSales);

    } catch (Exception e) {
        e.printStackTrace();
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR",
                "statusCode", 500,
                "message", "Failed to calculate merchant report: " + e.getMessage()
        ));
    }
}



    @PostMapping("/sale")
    @Operation(summary = "Record sale", description = "record sale made to customer (supports per-item discount/extra)")
    public ResponseEntity<?> recordSale(@RequestBody SaleRequest request) {
        try {
            // ── Resolve real merchantId ────────────────────────────────────────
            String userIdStr = request.getMerchantId();
            if (userIdStr == null || userIdStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "merchantId is required"
                ));
            }

            Long userId = Long.parseLong(userIdStr);
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "No user with specified ID exists"
                ));
            }

            String merchantId = userOpt.get().getMerchantId();
            if (merchantId == null || merchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "This user is not linked to any merchant"
                ));
            }

            log.info("SALE - Incoming userId: {} → Resolved to real merchantId: {}", userIdStr, merchantId);

            // ── Core sale logic ─────────────────────────────────────────────────
            String transactionRef = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            LocalDate saleDate = now.toLocalDate();

            List<SaleTransaction> saleTransactions = new ArrayList<>();
            BigDecimal totalAmount = BigDecimal.ZERO;

            // Step 1: Update inventory quantities (unchanged)
            List<Inventory> updatedInventories = inventoryService.recordMultipleSales(request);

            // Step 2: Build transactions with discount handling
            for (SaleItemRequest itemReq : request.getItems()) {
                Inventory inventory = inventoryService.getInventoryItemById(itemReq.getInventoryId());
                if (inventory == null) {
                    throw new IllegalArgumentException("Item not found with ID: " + itemReq.getInventoryId());
                }

                BigDecimal unitPrice = inventory.getUnitPrice() != null ? inventory.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal discount = itemReq.getDiscount() != null ? itemReq.getDiscount() : BigDecimal.ZERO;

                // Calculate line total with discount/extra
                BigDecimal baseAmount = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
                BigDecimal lineTotal = baseAmount.add(discount);  // discount positive → subtracts, negative → adds

                totalAmount = totalAmount.add(lineTotal);

                SaleTransaction transaction = SaleTransaction.builder()
                        .merchantId(merchantId)
                        .saleDate(saleDate)
                        .saleDateTime(now)
                        .itemName(inventory.getItemName())
                        .itemCode(inventory.getItemCode())
                        .quantity(itemReq.getQuantity())
                        .unitPrice(unitPrice)
                        .discount(discount)           // ← NEW: store per-line discount
                        .totalPrice(lineTotal)        // ← final amount after discount
                        .customerPhone(request.getCustomerPhone())
                        .transactionRef(transactionRef)
                        .build();

                saleTransactions.add(transaction);
            }

            // Step 3: Save all sale lines
            saleTransactionRepository.saveAll(saleTransactions);

            // Step 4: Update DailySalesSummary with final totalAmount
            DailySalesSummary summary = dailySalesSummaryRepository
                    .findByMerchantIdAndRecordDate(merchantId, saleDate)
                    .orElse(DailySalesSummary.builder()
                            .merchantId(merchantId)
                            .recordDate(saleDate)
                            .grossSales(BigDecimal.ZERO)
                            .deductions(BigDecimal.ZERO)
                            .netSales(BigDecimal.ZERO)
                            .build());

            summary.setGrossSales(summary.getGrossSales().add(totalAmount));
            summary.setNetSales(summary.getNetSales().add(totalAmount)); // adjust deductions if needed later

            dailySalesSummaryRepository.save(summary);

            // Step 5: Payment notification (unchanged)
            PaymentNotification paymentNotification = new PaymentNotification();
            paymentNotification.setTransactionType("Payment");
            paymentNotification.setTransID(transactionRef);
            paymentNotification.setTransTime(now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            paymentNotification.setTransAmount(totalAmount.setScale(0, RoundingMode.HALF_UP).intValue());
            paymentNotification.setPhoneNumber(request.getCustomerPhone());
            paymentNotification.setFirstName("Customer");
            paymentNotification.setMiddleName("");
            paymentNotification.setLastName("Payment");

            try {
                paymentService.recordPayment(paymentNotification);
            } catch (Exception e) {

                logger.info("Payment notification failed: {}", e.getMessage());
            }

            // Success response – include discount details
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Sale recorded successfully",
                    "transactionRef", transactionRef,
                    "totalAmount", totalAmount,
                    "itemsSold", saleTransactions.size(),
                    "data", updatedInventories,
                    "saleTransactions", saleTransactions  // optional: return full lines
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Failed to record sale", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to record sale: " + e.getMessage()
            ));
        }
    }



    @GetMapping("/sales/daily-items")
    @Operation(summary = "Get all sold items for a specific day - simplified view")
    public ResponseEntity<?> getDailySoldItems(
            @RequestParam String merchantId,   // ← This is actually the USER ID from UI/JWT
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        try {
            Long userId = Long.parseLong(merchantId);

            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant"
                ));
            }

            System.out.println("DAILY-ITEMS - Incoming userId: " + merchantId +
                    " → Resolved to real merchantId: " + realMerchantId);

            List<SaleTransaction> transactions = saleTransactionRepository
                    .findByMerchantIdAndSaleDateOrderBySaleDateTimeDesc(realMerchantId, date);

            // ── Original detailed items ────────────────────────────────────────
            List<Map<String, Object>> simplifiedItems = transactions.stream()
                    .map(t -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("itemName", t.getItemName());
                        item.put("itemCode", t.getItemCode());
                        item.put("quantity", t.getQuantity());
                        item.put("customerPhone", t.getCustomerPhone() != null ? t.getCustomerPhone() : "");
                        item.put("transactionRef", t.getTransactionRef());
                        item.put("totalPrice", t.getTotalPrice());
                        return item;
                    })
                    .toList();

            // ── New: Grouped summary per item ──────────────────────────────────
            Map<String, Map<String, Object>> summaryByCode = new LinkedHashMap<>();

            for (SaleTransaction t : transactions) {
                String code = t.getItemCode();

                Map<String, Object> group = summaryByCode.computeIfAbsent(code, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("itemName", t.getItemName());
                    m.put("itemCode", code);
                    m.put("totalQuantity", 0);
                    m.put("totalAmount", BigDecimal.ZERO);
                    m.put("timesSold", 0);
                    return m;
                });

                group.put("totalQuantity", (Integer) group.get("totalQuantity") + t.getQuantity());
                group.put("totalAmount", ((BigDecimal) group.get("totalAmount")).add(t.getTotalPrice()));
                group.put("timesSold", (Integer) group.get("timesSold") + 1);
            }

            List<Map<String, Object>> itemsSummary = new ArrayList<>(summaryByCode.values());

            // ── Original totals ────────────────────────────────────────────────
            BigDecimal dailyTotal = transactions.stream()
                    .map(SaleTransaction::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            int totalQuantitySold = transactions.stream()
                    .mapToInt(SaleTransaction::getQuantity)
                    .sum();

            // ── Final response ─────────────────────────────────────────────────
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "date", date.toString(),
                    "merchantId", realMerchantId,
                    "totalSalesAmount", dailyTotal,
                    "totalItemsSold", totalQuantitySold,
                    "numberOfTransactions", transactions.size(),
                    "items", simplifiedItems,           // ← original detailed lines
                    "summaryByItem", itemsSummary       // ← new grouped totals
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", "Invalid user ID format"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve daily sold items: " + e.getMessage()
            ));
        }
    }


@PutMapping("/{id}/update")
@Operation(summary = "Update inventory item", description = "Update item name, available stock quantity, unit price, and other details for a specific inventory item. merchantId is required for security.")
public ResponseEntity<?> updateInventoryItem(
        @PathVariable Long id,
        @RequestBody UpdateItemRequest request) {

    try {
        // === SAME PATTERN: merchantId in request is actually USER ID ===
        String userIdStr = request.getMerchantId();
        if (userIdStr == null || userIdStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", "merchantId is required"
            ));
        }

        Long userId = Long.parseLong(userIdStr);
        Optional<User> userOpt = userRepository.findById(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", "No user with specified ID exists"
            ));
        }

        String realMerchantId = userOpt.get().getMerchantId();

        if (realMerchantId == null || realMerchantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", "This user is not linked to any merchant"
            ));
        }

        // Optional log
        System.out.println("UPDATE ITEM " + id + " - User ID: " + userId +
                " → Real MerchantId: " + realMerchantId);

        // Optional: validate itemName not blank if provided
        if (request.getItemName() != null && request.getItemName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", "itemName cannot be empty if provided"
            ));
        }

        // Perform update with REAL merchantId
        Inventory updatedItem = inventoryService.updateInventoryItem(
                id,
                realMerchantId,                    // ← real one
                request.getItemName(),
                request.getQuantity(),
                request.getUnitPrice()
        );

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Inventory item updated successfully",
                "data", updatedItem
        ));

    } catch (NumberFormatException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "FAILURE",
                "message", "Invalid user ID format"
        ));
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "FAILURE",
                "message", e.getMessage()
        ));
    } catch (Exception e) {
        e.printStackTrace();
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR",
                "message", "Failed to update inventory item",
                "error", e.getMessage()
        ));
    }
}

    @DeleteMapping("/{id}/hard")
    @Operation(summary = "Permanently delete inventory item", description = "Hard delete - completely removes the item from database. Requires merchantId and is irreversible. Use with extreme caution!")
    public ResponseEntity<Map<String, Object>> hardDeleteInventoryItem(
            @PathVariable Long id,
            @RequestBody Map<String, String> requestBody) {

        try {
            // === SAME PATTERN: merchantId in body is actually USER ID ===
            String userIdStr = requestBody.get("merchantId");
            if (userIdStr == null || userIdStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "merchantId is required in request body"
                ));
            }

            Long userId = Long.parseLong(userIdStr);
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "No user with specified ID exists"
                ));
            }

            String realMerchantId = userOpt.get().getMerchantId();

            if (realMerchantId == null || realMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "This user is not linked to any merchant"
                ));
            }

            // Optional log
            System.out.println("HARD DELETE ITEM " + id + " - User ID: " + userId +
                    " → Real MerchantId: " + realMerchantId);

            // Perform hard delete with REAL merchantId
            inventoryService.hardDeleteInventoryItem(id, realMerchantId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Inventory item with ID " + id + " permanently deleted"
            ));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", "Invalid user ID format"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to permanently delete inventory item",
                    "error", e.getMessage()
            ));
        }
    }



}
