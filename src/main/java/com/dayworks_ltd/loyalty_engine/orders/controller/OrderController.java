package com.dayworks_ltd.loyalty_engine.orders.controller;


import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.dto.CreateMerchantRequest;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.RetailPaymentRequest;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.orders.CannotFulfillOrderException;
import com.dayworks_ltd.loyalty_engine.orders.dto.*;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;



@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final UserRepository userRepository;
    private final MerchantService merchantService;
    private final MerchantRepository merchantRepository;

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    /**
     * Merchant creates a new order + initiates M-Pesa STK Push
     */

    @GetMapping("/merchants/search")
    public ResponseEntity<?> searchMerchants(@RequestParam String query) {
        List<Merchant> matches = merchantService.search(query);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "results", matches
        ));
    }

    @GetMapping("/merchants/liquor-names")
    public ResponseEntity<List<String>> getLiquorMerchantNames() {
        return ResponseEntity.ok(merchantService.getLiquorMerchantNames());
    }


    @PostMapping("/merchants/create")
    public ResponseEntity<?> createMerchant(
            @AuthenticationPrincipal CustomUserDetails repDetails,
            @RequestBody CreateMerchantRequest request) {

        if (repDetails == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "message", "Unauthorized"
            ));
        }

        try {
            Merchant merchant = merchantService.createMerchantFromOrder(request, repDetails.getUserId());
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "merchantId", merchant.getId(),
                    "businessName", merchant.getBusinessName()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            logger.info("Error creating merchant from order", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to create merchant"
            ));
        }
    }
    @PostMapping("/create")
    @Operation(summary = "Create Order and Initiate Payment")
    public ResponseEntity<?> createOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody OrderRequest request) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "message", "Unauthorized"
            ));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            String distributorMerchantId = user.getMerchantId();

            if (distributorMerchantId == null || distributorMerchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "This user is not linked to any merchant/distributor"
                ));
            }

            // Validate that client sent the target merchantId
            if (request.getMerchantId() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "merchantId is required"
                ));
            }

            Order order = orderService.createOrder(distributorMerchantId, request);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Order created successfully. M-Pesa prompt sent to your phone.",
                    "orderCode", order.getOrderCode(),
                    "distributorId", distributorMerchantId,
                    "merchantId", request.getMerchantId(),
                    "totalAmount", order.getTotalAmount()
            ));

        } catch (IllegalArgumentException e) {
            logger.warn("Validation error in order creation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "statusCode", 400,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error creating order", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to create order"
            ));
        }
    }

    /**
     * Check payment status of an order
     */
    @GetMapping("/{orderCode}/payment-status")
    @Operation(summary = "Check M-Pesa Payment Status")
    public ResponseEntity<?> checkPaymentStatus(@PathVariable String orderCode) {

        try {
            Map<String, Object> result = orderService.checkPaymentStatus(orderCode);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "orderCode", orderCode,
                    "data", result
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }


    /**
     * Get all orders for the logged-in merchant
     */
    @GetMapping("/my-orders")
    @Operation(summary = "Get my orders")
    public ResponseEntity<?> getMyOrders(@AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty() || userOpt.get().getMerchantId() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "Merchant not found"
                ));
            }

            String merchantId = userOpt.get().getMerchantId();
            List<Order> orders = orderService.getOrdersByMerchant(merchantId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "count", orders.size(),
                    "data", orders
            ));
        } catch (Exception e) {
            logger.error("Error fetching my orders", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to fetch orders"
            ));
        }
    }

    /**
     * Get pending orders for distributor (for fulfillment)
     */
    @GetMapping("/distributor/pending")
    @Operation(summary = "Get pending paid orders for distributor")
    public ResponseEntity<?> getPendingOrdersForDistributor(@AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty() || userOpt.get().getMerchantId() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "Merchant not found"
                ));
            }

            String distributorId = userOpt.get().getMerchantId();
            List<Order> orders = orderService.getPendingOrdersForDistributor(distributorId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "count", orders.size(),
                    "data", orders
            ));
        } catch (Exception e) {
            logger.error("Error fetching pending orders", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to fetch pending orders"
            ));
        }
    }


    @PostMapping("/{orderCode}/fulfill")
    @Operation(summary = "Distributor fulfills a paid order")
    public ResponseEntity<?> fulfillOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String orderCode,
            @RequestParam(name = "allowPartial", defaultValue = "false") boolean allowPartial
    ) {
        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.getUserById(userId);
            String distributorId = user.getMerchantId();

            OrderFulfillmentResult result = orderService.fulfillOrder(orderCode, distributorId, userId, allowPartial);

            boolean isPartial = !result.insufficientItems().isEmpty();

            return ResponseEntity.ok(Map.of(
                    "status", isPartial ? "PARTIAL_SUCCESS" : "SUCCESS",
                    "message", isPartial
                            ? "Order fulfilled partially — some items could not be fully shipped"
                            : "Order fulfilled successfully",
                    "orderCode", orderCode,
                    "transferCode", result.stockTransfer().getTransferCode(),
                    "insufficientItems", result.insufficientItems()
            ));
        }
        catch (CannotFulfillOrderException e) {
            // 409: request was well-formed, but current stock state conflicts with fulfillment.
            // Do NOT return 200 here — callers must be able to trust HTTP status, not just parse the body.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage(),
                    "orderCode", orderCode,
                    "transferCode", "",
                    "insufficientItems", e.getInsufficientOrderItems()
            ));
        }
        catch (IllegalArgumentException e) {
            // order not found / wrong distributor / bad state transition — client error, not a stock conflict
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage()
            ));
        }
        catch (Exception e) {
            // Don't leak internal exception messages to the client
            logger.info("Unexpected error fulfilling order {}", orderCode, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "FAILURE",
                    "message", "Unable to fulfill order due to an unexpected error"
            ));
        }
    }



    @PostMapping("/{orderCode}/receive")
    @Operation(summary = "Merchant confirms stock receipt by entering order code from receipt")
    public ResponseEntity<?> receiveOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String orderCode) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "message", "Unauthorized"
            ));
        }

        try {
            Long userId = userDetails.getUserId();
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "User not found"
                ));
            }

            User user = userOpt.get();
            String merchantId = user.getMerchantId();

            if (merchantId == null || merchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "User is not linked to any merchant"
                ));
            }

            Order order = orderService.receiveOrder(orderCode, merchantId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Stock received successfully",
                    "orderCode", orderCode,
                    "receivedDate", order.getReceivedDate().toString()
            ));

        } catch (IllegalArgumentException e) {
            logger.warn("Receive order failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error receiving order", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to receive order"
            ));
        }
    }

    @GetMapping("/wholesalers/liquor")
    @Operation(summary = "Get all Liquor Wholesalers")
    public ResponseEntity<?> getLiquorWholesalers() {
        try {
            List<Merchant> wholesalers = merchantService.getLiquorWholesalers();

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "count", wholesalers.size(),
                    "data", wholesalers
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to fetch liquor wholesalers"
            ));
        }
    }

    @GetMapping("/merchant/status/{status}")
    public ResponseEntity<?> getMerchantOrdersByStatus(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable OrderStatus status) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "ERROR",
                    "message", "Unauthorized"
            ));
        }

        try {
            Long userId = userDetails.getUserId();
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "User not found"
                ));
            }

            String merchantId = userOpt.get().getMerchantId();

            if (merchantId == null || merchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "message", "User is not linked to any merchant"
                ));
            }

            List<Order> orders = orderService.getOrdersByMerchantAndStatus(merchantId, status);

            List<OrderResponseDTO> responseData = orders.stream()
                    .map(this::mapToOrderResponseDTO)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "role", "MERCHANT",
                    "filterStatus", status.name(),
                    "count", responseData.size(),
                    "data", responseData
            ));

        } catch (Exception e) {
            logger.error("Error fetching merchant orders", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to fetch orders"
            ));
        }
    }


    @GetMapping("/distributor/status/{status}")
    public ResponseEntity<?> getDistributorOrdersByStatus(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable OrderStatus status,
            @RequestParam(required = false) PaymentMode paymentMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "orderDate,desc") String sort) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILURE", "message", "User not found"));
            }

            String merchantId = userOpt.get().getMerchantId();
            if (merchantId == null || merchantId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILURE", "message", "User is not linked to any distributor"));
            }

            LocalDate effectiveStart = (startDate != null) ? startDate : LocalDate.now().minusDays(30);
            LocalDate effectiveEnd = (endDate != null) ? endDate : LocalDate.now();

            String[] sortParts = sort.split(",");
            Sort.Direction direction = sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortParts[0]));

            Page<Order> orderPage = orderService.getOrdersByDistributorAndStatus(
                    merchantId, status, paymentMode, effectiveStart, effectiveEnd, pageable);

            List<OrderResponseDTO> responseData = orderPage.getContent().stream()
                    .map(this::mapToOrderResponseDTO)
                    .toList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "SUCCESS");
            response.put("role", "DISTRIBUTOR");
            response.put("filterStatus", status.name());
            response.put("filterPaymentMode", paymentMode != null ? paymentMode.name() : "ALL");
            response.put("startDate", effectiveStart.toString());
            response.put("endDate", effectiveEnd.toString());
            response.put("page", page);
            response.put("size", size);
            response.put("totalElements", orderPage.getTotalElements());
            response.put("totalPages", orderPage.getTotalPages());
            response.put("count", responseData.size());
            response.put("data", responseData);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error fetching distributor orders", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", "Failed to fetch orders"));
        }
    }

    @PostMapping("/retail/collect-payment")
    @Operation(summary = "Field agent collects retail payment via STK (phone + amount only)")
    public ResponseEntity<?> collectRetailPayment(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody RetailPaymentRequest request) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }


        if (request == null || request.getPhoneNumber() == null || request.getAmount() == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILURE", "message", "Phone number and amount are required"));
        }

        try {
            Map<String, Object> result = orderService.collectStandaloneRetailPayment(
                    request.getPhoneNumber(),
                    request.getAmount()
            );

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "STK Push sent successfully",
                    "data", result
            ));

        } catch (Exception e) {
            logger.error("Failed to initiate retail payment", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to initiate payment"
            ));
        }
    }
    @PostMapping("/{orderCode}/collect-payment")
    @Operation(summary = "Field agent collects payment for PAY_ON_DELIVERY order via STK")
    public ResponseEntity<?> collectPayment(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String orderCode,
            @RequestBody(required = false) CollectPaymentRequest request) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            String overridePhone = (request != null) ? request.getPhoneNumber() : null;

            Map<String, Object> result = orderService.collectDeliveryPayment(orderCode, overridePhone);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "STK push sent",
                    "data", result
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILURE", "message", e.getMessage()));
        } catch (Exception e) {
            logger.info("Error collecting payment for order {}", orderCode, e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", "Failed to initiate payment collection"));
        }
    }


    private String getMerchantIdFromUser(CustomUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        Optional<User> userOpt = userRepository.findById(userId);

        if (userOpt.isEmpty() || userOpt.get().getMerchantId() == null ||
                userOpt.get().getMerchantId().isBlank()) {
            throw new IllegalArgumentException("Merchant ID not found for this user");
        }

        return userOpt.get().getMerchantId();
    }


    private OrderResponseDTO mapToOrderResponseDTO(Order order) {
        return OrderResponseDTO.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .orderDate(order.getOrderDate())
                .paymentDate(order.getPaymentDate())
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .totalAmount(order.getTotalAmount())
                .phoneNumber(order.getPhoneNumber())
                .checkoutRequestId(order.getCheckoutRequestId())
                .paymentReference(order.getPaymentReference())
                .merchant(mapMerchantSummary(order.getMerchant()))
                .distributor(mapMerchantSummary(order.getDistributor()))
                .items(order.getItems() != null ?
                        order.getItems().stream().map(this::mapOrderItem).toList() : List.of())
                .build();
    }

    private MerchantSummaryDTO mapMerchantSummary(Merchant merchant) {
        if (merchant == null) return null;
        return MerchantSummaryDTO.builder()
                .id(merchant.getId())
                .businessName(merchant.getBusinessName())
                .businessType(merchant.getBusinessType())
                .location(merchant.getLocation())
                .tillNumber(merchant.getTillNumber())
                .businessPhone(merchant.getBusinessPhone())
                .build();
    }

    private OrderItemDTO mapOrderItem(OrderItem item) {
        return OrderItemDTO.builder()
                .id(item.getId())
                .itemCode(item.getItemCode())
                .itemName(item.getItemName())
                .quantity(item.getQuantity())
                .wholesalePrice(item.getWholesalePrice())
                .lineTotal(item.getLineTotal())
                .build();
    }



    private String normalizePhone(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("254")) return "0" + digits.substring(3);
        if (digits.startsWith("0")) return digits;
        if (digits.length() == 9) return "0" + digits; // missing leading 0
        return digits;
    }


}