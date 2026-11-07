package com.dayworks_ltd.loyalty_engine.orders.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponseDTO {

    private Long id;
    private String orderCode;
    private LocalDateTime orderDate;
    private LocalDateTime paymentDate;
    private String status;
    private BigDecimal totalAmount;
    private String phoneNumber;
    private String checkoutRequestId;
    private String paymentReference;

    // Merchant Info (minimal)
    private MerchantSummaryDTO merchant;

    // Distributor Info (minimal)
    private MerchantSummaryDTO distributor;

    private List<OrderItemDTO> items;

    private BigDecimal projectedMargin;
    private BigDecimal marginCoverageRatio;
    private boolean marginDataComplete;

    // Distributor's own margin on this order (only filled for the distributor's order list, never for the shop)
    /** Total cost to the distributor: sum(quantity x unit cost from inventory). Null if any cost is missing. */
    private BigDecimal costKes;
    /** What the distributor made: order total - cost. Null if any cost is missing. */
    private BigDecimal marginKes;
    /** marginKes / order total x 100. */
    private BigDecimal marginPct;
    private Boolean marginComplete;

    /** Who placed it: DISTRIBUTOR, REP or SHOP (null on old orders = DISTRIBUTOR). */
    private String orderSource;
    /** Items the shop asked for that aren't in the catalogue (from the shop order link). */
    private String shopNote;
    /** Why the order was cancelled, and when (null unless CANCELLED). */
    private String cancelReason;
    /** Consumer (WhatsApp) orders: who ordered and where to deliver. */
    private String customerName;
    private String deliveryAddress;
    private LocalDateTime cancelledAt;
}