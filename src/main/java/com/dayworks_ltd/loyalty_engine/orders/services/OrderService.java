package com.dayworks_ltd.loyalty_engine.orders.services;
import com.dayworks_ltd.loyalty_engine.auth.enums.TransferType;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferItemRequest;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferRequest;
import com.dayworks_ltd.loyalty_engine.inventory.models.DailySalesSummary;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.SaleTransaction;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.DailySalesSummaryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.SaleTransactionRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.InventoryService;
import com.dayworks_ltd.loyalty_engine.inventory.services.ProductPerformanceService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockTransferService;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.orders.CannotFulfillOrderException;
import com.dayworks_ltd.loyalty_engine.orders.dto.InsufficientOrderItem;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderFulfillmentResult;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderItemRequest;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderRequest;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderItem;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final MerchantRepository merchantRepository;
    private final InventoryRepository inventoryRepository;
    private final RestTemplate restTemplate;
    private final StockTransferService stockTransferService;
    private final ProductPerformanceService productPerformanceService;

    @Autowired
    private SaleTransactionRepository saleTransactionRepository;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private DailySalesSummaryRepository dailySalesSummaryRepository;

    @Value("${payment.base-url}")
    private String paymentBaseUrl;

    /**
     * Create Order + Initiate M-Pesa STK Push
     */
    @Transactional
    public Order createOrder(String distributorMerchantId, OrderRequest request) {

        // Distributor comes from logged-in user
        Merchant distributor = merchantRepository.findById(Long.parseLong(distributorMerchantId))
                .orElseThrow(() -> new IllegalArgumentException("Distributor not found"));

        // Merchant (retailer) comes from request body
        Merchant merchant = merchantRepository.findById(Long.valueOf(request.getMerchantId()))
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));

        String orderCode = generateOrderCode();
        BigDecimal calculatedTotal = calculateTotal(request.getItems());

        OrderStatus initialStatus = request.getPaymentMode() == PaymentMode.PAY_ON_DELIVERY
                ? OrderStatus.PENDING
                : OrderStatus.PENDING_PAYMENT;

        Order order = Order.builder()
                .orderCode(orderCode)
                .merchant(merchant)           // Retailer
                .distributor(distributor)     // Logged-in Distributor
                .orderDate(LocalDateTime.now())
                .status(initialStatus)
                .paymentMode(request.getPaymentMode())
                .totalAmount(calculatedTotal)
                .phoneNumber(request.getPhoneNumber())
                .build();

        for (OrderItemRequest itemReq : request.getItems()) {
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .itemCode(itemReq.getItemCode())
                    .itemName(itemReq.getItemName())
                    .quantity(itemReq.getQuantity())
                    .wholesalePrice(itemReq.getWholesalePrice())
                    .lineTotal(itemReq.getWholesalePrice()
                            .multiply(BigDecimal.valueOf(itemReq.getQuantity())))
                    .build();
            order.addItem(item);
        }
        String normalizedPhone = normalizeMsisdn(request.getPhoneNumber());

        // PREPAID: STK Push
        if (request.getPaymentMode() == PaymentMode.PREPAID) {
            log.info("Initiating STK push for order {} | phone={} | amount={} | items={}",
                    orderCode,
                    normalizedPhone,
                    calculatedTotal,
                    request.getItems().stream()
                            .map(i -> i.getItemCode() + ":qty=" + i.getQuantity() + ":price=" + i.getWholesalePrice())
                            .collect(Collectors.joining(", ")));

            String checkoutRequestId = initiateStkPush(
                    normalizedPhone,
                    calculatedTotal,
                    orderCode
            );

            log.info("checking checkoutRequestId {}", checkoutRequestId);
            order.setCheckoutRequestId(checkoutRequestId);
        }

        Order savedOrder = orderRepository.save(order);

        if (request.getPaymentMode() == PaymentMode.PAY_ON_DELIVERY) {
            log.info("PAY_ON_DELIVERY order {} created by distributor {} for merchant {}",
                    orderCode, distributorMerchantId, request.getMerchantId());
        }

        return savedOrder;
    }

    private String normalizeMsisdn(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) {
            return "254" + digits.substring(1);
        }
        if (digits.startsWith("254") && digits.length() == 12) {
            return digits;
        }
        if (digits.startsWith("7") && digits.length() == 9) {
            return "254" + digits;
        }
        throw new IllegalArgumentException("Invalid phone number format: " + phone);
    }
    // Extracted STK method — throws if it fails, @Transactional rolls everything back
    private String initiateStkPush(String phoneNumber, BigDecimal amount, String orderCode) {
        Map<String, Object> stkRequest = Map.of(
                "phoneNumber", phoneNumber,
                "amount", amount
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
                paymentBaseUrl + "/api/v1/payment/initiate-stk-push",
                stkRequest,
                Map.class
        );
        log.info("STK Push Raw Response: {}", response);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Payment initiation failed for order " + orderCode);
        }

        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        String checkoutRequestId = (String) data.get("paymentReference");

        if (checkoutRequestId == null || checkoutRequestId.isBlank()) {
            throw new RuntimeException("Invalid STK response — no CheckoutRequestID for order " + orderCode);
        }

        log.info("STK Push initiated for order {} | CheckoutRequestID: {}", orderCode, checkoutRequestId);
        return checkoutRequestId;
    }
    /**
     * Check Payment Status
     */
    @Transactional
    public Map<String, Object> checkPaymentStatus(String orderCode) {

        Order order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (order.getCheckoutRequestId() == null) {
            throw new IllegalArgumentException("No payment initiated for this order");
        }

        try {
            Map<String, Object> confirmRequest = Map.of(
                    "paymentReference", order.getCheckoutRequestId()
            );

            log.info("Checking payment status for Order: {} | CheckoutRequestID: {}",
                    orderCode, order.getCheckoutRequestId());

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    paymentBaseUrl + "/api/v1/payment/check-stk-push-status",
                    confirmRequest,
                    Map.class
            );

            // === DETAILED SAFARICOM RESPONSE LOGGING ===
            log.info("Safaricom Response for Order {}: {}", orderCode, response.getBody());

            if (response.getBody() != null) {
                log.info("Safaricom Raw Response: {}", response.getBody());

                Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                if (data != null) {
                    log.info("Safaricom ResponseCode: {}", data.get("ResponseCode"));
                    log.info("Safaricom ResponseDescription: {}", data.get("ResponseDescription"));
                    log.info("Safaricom ConversationID: {}", data.get("ConversationID"));
                }
            }

            boolean isPaid = isPaymentSuccessful(response.getBody());

            if (isPaid) {

                String mpesaReceipt = null;
                if (response.getBody() != null) {
                    Object dataObj = response.getBody().get("data");
                    if (dataObj instanceof Map) {
                        Object receipt = ((Map<?, ?>) dataObj).get("MpesaReceipt");
                        if (receipt != null) {
                            mpesaReceipt = receipt.toString().trim();
                        }
                    }
                }
                order.setStatus(OrderStatus.PAID);
                order.setPaymentDate(LocalDateTime.now());
                order.setPaymentReference(
                        mpesaReceipt != null && !mpesaReceipt.isBlank()
                                ? mpesaReceipt
                                : order.getCheckoutRequestId()
                );
                orderRepository.save(order);

                log.info("✅ PAYMENT SUCCESSFUL - Order {} marked as PAID", orderCode);

                log.info("✅ PAYMENT SUCCESSFUL - Order {} marked as PAID. Attempting auto-fulfillment...", orderCode);

                // === AUTO FULFILLMENT ===
                try {
                    autoFulfillOrder(order);
                } catch (Exception autoEx) {
                    log.warn("Auto-fulfillment failed for order {} (payment was successful). Manual fulfillment still possible.",
                            orderCode, autoEx);
                    // Do NOT fail the payment confirmation
                }
            } else {
                log.warn("❌ Payment NOT confirmed yet for Order: {}", orderCode);
            }

            return Map.of(
                    "orderCode", orderCode,
                    "orderStatus", order.getStatus().name(),
                    "isPaid", isPaid,
                    "paymentResponse", response.getBody()
            );

        } catch (Exception e) {
            log.error("❌ Error checking payment status for order {}", orderCode, e);
            throw new RuntimeException("Failed to check payment status");
        }
    }


    private void autoFulfillOrder(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            return;
        }

        // Determine distributor from the order (this is the key piece)
        String distributorMerchantId = order.getDistributor() != null
                ? order.getDistributor().getId().toString()
                : null;

        if (distributorMerchantId == null || distributorMerchantId.isBlank()) {
            log.warn("Cannot auto-fulfill order {} - no distributor linked", order.getOrderCode());
            return;
        }

        // Use a system/internal user ID for issuedByUserId (create a dedicated system user if possible)
        // Or use the merchant's user who created the order if you have that info
        Long systemUserId = 1L; // TODO: Replace with proper system user ID or order creator

        try {
            OrderFulfillmentResult result = fulfillOrder(
                    order.getOrderCode(),
                    distributorMerchantId,
                    systemUserId,
                    false  // allowPartial = true for auto-fulfillment (recommended)
            );

            log.info("✅ Auto-fulfillment completed for order {}. Transfer: {}",
                    order.getOrderCode(), result.stockTransfer().getTransferCode());

        } catch (CannotFulfillOrderException e) {
            log.info("Auto-fulfillment partial failure for order {}: {}",
                    order.getOrderCode(), e.getMessage());
            // Still success for payment, just stock issue
        } catch (IllegalArgumentException e) {
            log.warn("Auto-fulfillment skipped for order {}: {}", order.getOrderCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during auto-fulfillment of order {}", order.getOrderCode(), e);
        }
    }

    // ==================== Other Methods ====================
    public List<Order> getOrdersByMerchant(String merchantId) {
        return orderRepository.findByMerchantIdOrderByOrderDateDesc(Long.parseLong(merchantId));
    }

    public List<Order> getPendingOrdersForDistributor(String distributorId) {
        return orderRepository.findByDistributorIdAndStatus(
                Long.parseLong(distributorId), OrderStatus.PAID);
    }

//    public List<Order> getPendingOrdersForDistributor(String distributorId) {
//        return orderRepository.findByDistributorIdAndStatus(distributorId, OrderStatus.PAID);
//    }

    /**
     * Get orders for a Merchant by status
     */
    public List<Order> getOrdersByMerchantAndStatus(String merchantId, OrderStatus status) {
        return orderRepository.findByMerchantIdAndStatus(
                Long.parseLong(merchantId), status);
    }

    /**
     * Get orders for a Distributor by status
     */
    public Page<Order> getOrdersByDistributorAndStatus(
            String distributorId, OrderStatus status, PaymentMode paymentMode,
            LocalDate startDate, LocalDate endDate, Pageable pageable) {

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);
        Long distId = Long.parseLong(distributorId);

        if (paymentMode != null) {
            return orderRepository.findByDistributorIdAndStatusAndPaymentModeAndOrderDateBetween(
                    distId, status, paymentMode, start, end, pageable);
        }
        return orderRepository.findByDistributorIdAndStatusAndOrderDateBetween(
                distId, status, start, end, pageable);
    }






    public List<Order> getOrdersByDistributorAndStatus(String distributorId, OrderStatus status) {
        return orderRepository.findByDistributorIdAndStatus(
                Long.parseLong(distributorId), status);
    }
    // ====================== Helpers ======================

    private String generateOrderCode() {
        int code = (int)(Math.random() * 900000) + 100000;
        return String.valueOf(code);
    }

    private BigDecimal calculateTotal(List<OrderItemRequest> items) {
        return items.stream()
                .map(item -> item.getWholesalePrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private boolean isPaymentSuccessful(Map<String, Object> responseBody) {
        if (responseBody == null) return false;

        Object statusObj = responseBody.get("status");
        if (statusObj == null) return false;

        // status comes back as integer 200
        int status = ((Number) statusObj).intValue();
        if (status != 200) return false;

        Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
        if (data == null) return false;

        // ResultCode comes back as String "0"
        Object resultCode = data.get("ResultCode");
        if (resultCode == null) return false;

        return "0".equals(resultCode.toString());
    }

    @Transactional
    public OrderFulfillmentResult fulfillOrder(String orderCode, String distributorMerchantId, Long issuedByUserId, boolean allowPartial) {

        Order order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        switch (order.getStatus()) {
            case FULFILLED -> throw new IllegalArgumentException(
                    "Order already fulfilled. Transfer: " + order.getStockTransfer().getTransferCode()
            );
            case RECEIVED -> throw new IllegalArgumentException("Order already received by merchant");
            case CANCELLED -> throw new IllegalArgumentException("Order has been cancelled");
            case PENDING, PAID -> {}
        }

        if (!order.getDistributor().getId().toString().equals(distributorMerchantId)) {
            throw new IllegalArgumentException("You are not the distributor for this order");
        }

        ArrayList<InsufficientOrderItem> insufficientOrderItems = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();

        validateDistributorStock(order, distributorMerchantId, allowPartial, orderItems, insufficientOrderItems);

        // Merge insufficient items back in ONLY where partial stock actually exists.
        // Zero-availability items are never added — no phantom zero-quantity transfer/sale rows.
        for (InsufficientOrderItem insufficientOrderItem : insufficientOrderItems) {
            if (insufficientOrderItem.availableStock() <= 0) {
                continue; // nothing to ship, nothing to sell — stays flagged in insufficientOrderItems only
            }
            orderItems.add(
                    OrderItem.builder()
                            .itemCode(insufficientOrderItem.itemCode())
                            .itemName(insufficientOrderItem.itemName()) // FIX: was itemCode() — corrupted names on every partial fulfillment
                            .quantity(insufficientOrderItem.availableStock())
                            .wholesalePrice(insufficientOrderItem.wholesalePrice())
                            .build()
            );
        }

        if (orderItems.isEmpty()) {
            // Nothing at all could be fulfilled — every item was out of stock.
            // Don't create an empty StockTransfer; surface this as a full failure.
            throw new CannotFulfillOrderException(
                    "No items could be fulfilled — all items are out of stock",
                    insufficientOrderItems
            );
        }

        StockTransferRequest transferRequest = createTransferRequestFromOrder(
                order.getOrderCode(),
                order.getDistributor().getId(),
                order.getMerchant().getId(),
                orderItems
        );

        StockTransfer stockTransfer = stockTransferService.createStockTransfer(transferRequest, issuedByUserId);

        order.markAsFulfilled(stockTransfer);
        orderRepository.save(order);

        recordFulfillmentSale(distributorMerchantId, orderItems, stockTransfer.getTransferCode(), LocalDateTime.now());

        log.info("Order {} fulfilled with Stock Transfer {} ({} items shipped, {} items flagged insufficient)",
                orderCode, stockTransfer.getTransferCode(), orderItems.size(), insufficientOrderItems.size());

        return new OrderFulfillmentResult(stockTransfer, insufficientOrderItems);
    }
    private void recordFulfillmentSale(
            String distributorMerchantId,
            List<OrderItem> orderItems,
            String transactionRef,
            LocalDateTime now
    ) {
        List<SaleTransaction> transactions = new ArrayList<>();

        for (OrderItem item : orderItems) {
            Inventory inventory = inventoryService.getInventoryByMerchantIdAndItemCode(
                    distributorMerchantId, item.getItemCode());

            BigDecimal unitCost = (inventory != null && inventory.getUnitCost() != null)
                    ? inventory.getUnitCost() : BigDecimal.ZERO;

            if (unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Fulfillment sale: zero/null unit_cost for item {} distributor {} — recording anyway",
                        item.getItemCode(), distributorMerchantId);
            }

            BigDecimal unitPrice = item.getWholesalePrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));

            transactions.add(SaleTransaction.builder()
                    .merchantId(distributorMerchantId)
                    .saleDate(now.toLocalDate())
                    .saleDateTime(now)
                    .itemName(item.getItemName())
                    .itemCode(item.getItemCode())
                    .quantity(item.getQuantity())
                    .unitPrice(unitPrice)
                    .unitCost(unitCost)
                    .totalPrice(lineTotal)
                    .transactionRef(transactionRef)
                    .orderType("WHOLESALE")
                    .build());
        }

        saleTransactionRepository.saveAll(transactions);

        DailySalesSummary summary = dailySalesSummaryRepository
                .findByMerchantIdAndRecordDate(distributorMerchantId, now.toLocalDate())
                .orElse(DailySalesSummary.builder()
                        .merchantId(distributorMerchantId)
                        .recordDate(now.toLocalDate())
                        .grossSales(BigDecimal.ZERO)
                        .deductions(BigDecimal.ZERO)
                        .netSales(BigDecimal.ZERO)
                        .build());

        BigDecimal fulfillmentTotal = transactions.stream()
                .map(SaleTransaction::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        summary.setGrossSales(summary.getGrossSales().add(fulfillmentTotal));
        summary.setNetSales(summary.getNetSales().add(fulfillmentTotal));
        dailySalesSummaryRepository.save(summary);
    }
    private List<InsufficientOrderItem> validateDistributorStock(
            Order order, String distributorMerchantId, boolean allowPartial,
            List<OrderItem> orderItems,
            List<InsufficientOrderItem> insufficientOrderItems
    ) {
        ArrayList<String> insufficientItems = new ArrayList<>();
        orderItems.addAll(order.getItems());   // mutate the caller's list, don't replace it

        for (OrderItem item : order.getItems()) {
            Optional<Inventory> stockOpt = inventoryRepository
                    .findByMerchantIdAndItemCode(distributorMerchantId, item.getItemCode());

            if (stockOpt.isEmpty()) {
                insufficientItems.add(item.getItemName() + " (" + item.getItemCode() + ") - not found in distributor stock");
                insufficientOrderItems.add(new InsufficientOrderItem(
                        item.getItemCode(),
                        item.getItemName(),
                        item.getQuantity(),
                        item.getWholesalePrice(),
                        0,
                        "Item not found in distributor stock"
                ));

                if (allowPartial) {
                    orderItems.remove(item);
                }
                continue;
            }

            Inventory stock = stockOpt.get();

            if (stock.getAvailableStock() < item.getQuantity()) {
                insufficientItems.add(
                        item.getItemName() + " (" + item.getItemCode() + ")" +
                                " - Required: " + item.getQuantity() +
                                ", Available: " + stock.getAvailableStock()
                );

                insufficientOrderItems.add(new InsufficientOrderItem(
                        item.getItemCode(),
                        item.getItemName(),
                        item.getQuantity(),
                        item.getWholesalePrice(),
                        stock.getAvailableStock(),
                        "Items in stock cannot fully fulfill the order"
                ));

                if (allowPartial) {
                    orderItems.remove(item);
                }
            }
        }

        if (!allowPartial && !insufficientOrderItems.isEmpty()) {
            throw new CannotFulfillOrderException(
                    "Insufficient distributor stock for: " + String.join(" | ", insufficientItems),
                    insufficientOrderItems
            );
        }

        return insufficientOrderItems;
    }


    private StockTransferRequest createTransferRequestFromOrder(String orderCode, Long distributorId, Long merchantId, List<OrderItem> orderItems) {
        List<StockTransferItemRequest> items = orderItems.stream()
                .map(item -> StockTransferItemRequest.builder()
                        .itemCode(item.getItemCode())
                        .itemName(item.getItemName())
                        .quantity(item.getQuantity())
                        .wholesaleUnitPrice(item.getWholesalePrice())
                        .build())
                .toList();

        return StockTransferRequest.builder()
                .distributorId(distributorId)
                .recipientId(merchantId)
                .transferType(TransferType.ORDER_FULFILLMENT)
                .items(items)
                .notes("Fulfillment for Order: " + orderCode)
                .build();
    }
    public Map<String, Object> collectStandaloneRetailPayment(String phoneNumber, BigDecimal amount) {

        if (phoneNumber == null || phoneNumber.isBlank() || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Valid phone number and amount are required");
        }
        String normalizedPhone = normalizeMsisdn(phoneNumber);




        // Initiate STK Push
        String checkoutRequestId = initiateStkPush(phoneNumber, amount, "RETAIL-" + System.currentTimeMillis());

        log.info("Retail standalone payment initiated | Phone: {} | Amount: {} | CheckoutID: {}",
                normalizedPhone, amount, checkoutRequestId);

        return Map.of(
                "checkoutRequestId", checkoutRequestId,
                "phoneNumber", phoneNumber,
                "amount", amount,
                "transactionRef", "RETAIL-" + Instant.now().toEpochMilli()
        );
    }
    @Transactional
    public Map<String, Object> collectDeliveryPayment(String orderCode, String overridePhone) {

        Order order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (order.getPaymentMode() != PaymentMode.PAY_ON_DELIVERY) {
            throw new IllegalArgumentException("This order is not PAY_ON_DELIVERY");
        }

        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.RECEIVED) {
            throw new IllegalArgumentException("Order already completed — cannot collect payment");
        }

        String phoneToUse = (overridePhone != null && !overridePhone.isBlank())
                ? overridePhone
                : order.getPhoneNumber();

        if (phoneToUse == null || phoneToUse.isBlank()) {
            throw new IllegalArgumentException("No phone number available for this order");
        }

        // STK push fires first — nothing is persisted until this succeeds
        String checkoutRequestId = initiateStkPush(phoneToUse, order.getTotalAmount(), orderCode);

        // Only store checkoutRequestId AFTER the push succeeds — you can't check status
        // on a request you never made
        order.setCheckoutRequestId(checkoutRequestId);
        orderRepository.save(order);

        Map<String, Object> response = new HashMap<>();
        response.put("orderCode", orderCode);
        response.put("phoneUsed", phoneToUse);
        response.put("amount", order.getTotalAmount());
        response.put("checkoutRequestId", checkoutRequestId);
        return response;
    }
    @Transactional
    public Order receiveOrder(String orderCode, String receivingMerchantId) {

        // 1. Find order
        Order order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderCode));

        // 2. Status guard
        switch (order.getStatus()) {
            case PENDING -> throw new IllegalArgumentException("Order has not been paid yet");
            case PAID -> throw new IllegalArgumentException("Order has not been fulfilled yet");
            case RECEIVED -> throw new IllegalArgumentException("Order already received");
            case CANCELLED -> throw new IllegalArgumentException("Order has been cancelled");
            case FULFILLED -> {} // valid — proceed
            default -> throw new IllegalStateException("Unknown order status: " + order.getStatus());
        }

        // 3. Must be the correct merchant
        if (!order.getMerchant().getId().toString().equals(receivingMerchantId)) {
            throw new IllegalArgumentException("You are not the recipient of this order");
        }

        // 4. Process each item
        for (OrderItem item : order.getItems()) {
            String productSuffix = extractProductSuffix(item.getItemCode());
            String receiverItemCode = receivingMerchantId + "-" + productSuffix;

            Optional<Inventory> stockOpt = inventoryRepository
                    .findByMerchantIdAndItemCode(receivingMerchantId, receiverItemCode);

            if (stockOpt.isPresent()) {
                // Item exists — update stock
                Inventory stock = stockOpt.get();
                stock.applyRestock(item.getQuantity(), item.getWholesalePrice());
                inventoryRepository.save(stock);

                // Track restock in performance
                try {
                    productPerformanceService.updateRestock(stock, item.getQuantity());
                } catch (Exception e) {
                    log.warn("Performance restock update failed for item {}: {}",
                            receiverItemCode, e.getMessage());
                }

            } else {
                // Item does not exist — create new inventory record
                Inventory newStock = Inventory.builder()
                        .merchantId(receivingMerchantId)
                        .itemCode(receiverItemCode)
                        .itemName(item.getItemName())
                        .startingStock(0)                 // always zero for new items
                        .addedStock(item.getQuantity())   // received quantity goes here
                        .soldStock(0)
                        .availableStock(item.getQuantity())
                        .closingStock(item.getQuantity())
                        .wholesalePrice(item.getWholesalePrice())
                        .unitCost(item.getWholesalePrice())
                        .unitPrice(null)
                        .reorderLevel(10)
                        .isActive(true)
                        .recordDate(LocalDate.now())
                        .lastUpdated(LocalDateTime.now())
                        .lastRestockDate(LocalDateTime.now())
                        .totalSales(BigDecimal.ZERO)
                        .grossSales(BigDecimal.ZERO)
                        .netlSales(BigDecimal.ZERO)
                        .deductions(BigDecimal.ZERO)
                        .build();

                inventoryRepository.save(newStock);
                log.info("New inventory item created for merchant {} — item: {}",
                        receivingMerchantId, receiverItemCode);
            }
        }

        // 5. Mark order received
        order.markAsReceived();
        orderRepository.save(order);

        log.info("Order {} received by merchant {}", orderCode, receivingMerchantId);
        return order;
    }
    private String extractProductSuffix(String itemCode) {
        int dashIndex = itemCode.indexOf('-');
        return dashIndex >= 0 ? itemCode.substring(dashIndex + 1) : itemCode;
    }
}