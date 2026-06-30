package com.dayworks_ltd.loyalty_engine.orders.dto;


import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderRequest {


    private String merchantId;

    private List<OrderItemRequest> items;
    private PaymentMode paymentMode;
    private String phoneNumber;
}