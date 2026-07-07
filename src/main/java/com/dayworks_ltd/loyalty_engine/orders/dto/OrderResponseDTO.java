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
}