package com.dayworks_ltd.loyalty_engine.orders.services;
import com.dayworks_ltd.loyalty_engine.auth.enums.TransferType;
import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferItemRequest;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferRequest;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.services.ProductPerformanceService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockTransferService;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.orders.CannotFulfillOrderException;
import com.dayworks_ltd.loyalty_engine.orders.dto.InsufficientOrderItem;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final MerchantRepository merchantRepository;
    private final InventoryRepository inventoryRepository;
    private final RestTemplate restTemplate;
    private final StockTransferService stockTransferService;
    private final ProductPerformanceService productPerformanceService; // add this


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

        // PREPAID: STK Push
        if (request.getPaymentMode() == PaymentMode.PREPAID) {
            String checkoutRequestId = initiateStkPush(
                    request.getPhoneNumber(),
                    calculatedTotal,
                    orderCode
            );
            order.setCheckoutRequestId(checkoutRequestId);
        }

        Order savedOrder = orderRepository.save(order);

        if (request.getPaymentMode() == PaymentMode.PAY_ON_DELIVERY) {
            log.info("PAY_ON_DELIVERY order {} created by distributor {} for merchant {}",
                    orderCode, distributorMerchantId, request.getMerchantId());
        }

        return savedOrder;
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

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Payment initiation failed for order " + orderCode);
        }

        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        String checkoutRequestId = (String) data.get("CheckoutRequestID");

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
                    "CheckoutRequestId", order.getCheckoutRequestId()
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
                order.setStatus(OrderStatus.PAID);
                order.setPaymentDate(LocalDateTime.now());
                order.setPaymentReference(order.getCheckoutRequestId());
                orderRepository.save(order);

                log.info("✅ PAYMENT SUCCESSFUL - Order {} marked as PAID", orderCode);
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
    public List<Order> getOrdersByDistributorAndStatus(String distributorId, OrderStatus status, PaymentMode paymentMode) {
        if (paymentMode != null) {
            return orderRepository.findByDistributorAndStatusAndPaymentMode(Long.valueOf(distributorId), status, paymentMode);
        }
        return orderRepository.findByDistributorIdAndStatus(
                Long.parseLong(distributorId), status);
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
    public StockTransfer fulfillOrder(String orderCode, String distributorMerchantId, Long issuedByUserId, boolean allowPartial) {

        Order order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        List<OrderItem> originalOrderItems = new ArrayList<>(order.getItems());

        switch (order.getStatus()) {
            case PENDING -> throw new IllegalArgumentException("Order has not been paid yet");
            case FULFILLED -> throw new IllegalArgumentException(
                    "Order already fulfilled. Transfer: " + order.getStockTransfer().getTransferCode()
            );
            case RECEIVED -> throw new IllegalArgumentException("Order already received by merchant");
            case CANCELLED -> throw new IllegalArgumentException("Order has been cancelled");
            case PAID -> {} // valid — proceed
        }


        if (!order.getDistributor().getId().toString().equals(distributorMerchantId)) {
            throw new IllegalArgumentException("You are not the distributor for this order");
        }


        log.info("\n\n\n");
        log.info("Fulfill partial: {}", allowPartial);
        log.info("\n\n\n");
        //Validate order items. Separate order items that are not in inventory and those that are low in stock
        //from order items that are available and have enough stock

        ArrayList<InsufficientOrderItem> insufficientOrderItems = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();

        validateDistributorStock(order, distributorMerchantId, allowPartial, orderItems, insufficientOrderItems);
        //at this point, insufficient order items contains items that are not available in inventory
        //or those that are low in stock
        //orderItems remains with those that are available in inventory and have enough stock
        //to fulfill order request

        for(InsufficientOrderItem insufficientOrderItem : insufficientOrderItems)
        {
            orderItems.add(
                    OrderItem.builder()
                            .itemCode(insufficientOrderItem.itemCode())
                            .itemName(insufficientOrderItem.itemCode())
                            .quantity(insufficientOrderItem.availableStock())
                            .wholesalePrice(insufficientOrderItem.wholesalePrice())
                            .build()
            );
        }


        // Create Stock Transfer from Order
        StockTransferRequest transferRequest = createTransferRequestFromOrder(
                order.getOrderCode(),
                order.getDistributor().getId(),
                order.getMerchant().getId(),
                orderItems
        );

        StockTransfer stockTransfer = stockTransferService.createStockTransfer(transferRequest, issuedByUserId);

        // Link them
        order.markAsFulfilled(stockTransfer);
        orderRepository.save(order);

        log.info("Order {} fulfilled with Stock Transfer {}", orderCode, stockTransfer.getTransferCode());

        return stockTransfer;
    }


    private List<InsufficientOrderItem> validateDistributorStock(
            Order order, String distributorMerchantId, boolean allowPartial,
            List<OrderItem> orderItems,
            List<InsufficientOrderItem> insufficientOrderItems
    ) {
        ArrayList<String> insufficientItems = new ArrayList<>();
        orderItems = new ArrayList<>(order.getItems());

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

                if(allowPartial)
                {
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

                if(allowPartial)
                {
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