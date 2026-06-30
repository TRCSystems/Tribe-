package com.dayworks_ltd.loyalty_engine.orders.controller;


import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.dto.CreateMerchantRequest;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.orders.dto.*;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
            @PathVariable String orderCode) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.getUserById(userId);
            String distributorId = user.getMerchantId();

            StockTransfer transfer = orderService.fulfillOrder(orderCode, distributorId, userId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Order fulfilled successfully",
                    "orderCode", orderCode,
                    "transferCode", transfer.getTransferCode()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILURE",
                    "message", e.getMessage()
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
            @RequestParam(required = false) PaymentMode paymentMode) {

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

            List<Order> orders = orderService.getOrdersByDistributorAndStatus(merchantId, status, paymentMode);

            List<OrderResponseDTO> responseData = orders.stream()
                    .map(this::mapToOrderResponseDTO)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "role", "DISTRIBUTOR",
                    "filterStatus", status.name(),
                    "filterPaymentMode", paymentMode != null ? paymentMode.name() : "ALL",
                    "count", responseData.size(),
                    "data", responseData
            ));

        } catch (Exception e) {
            logger.error("Error fetching distributor orders", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", "Failed to fetch orders"));
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