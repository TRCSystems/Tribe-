package com.dayworks_ltd.loyalty_engine.inventory.DTO;

import com.dayworks_ltd.loyalty_engine.auth.enums.TransferType;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransferRequest {

    private Long distributorId;           // Merchant ID of Distributor
    private Long recipientId;             // Merchant ID of Retailer
    private TransferType transferType;    // MANUAL_PICKUP or ORDER_FULFILLMENT
    private String notes;
    @Builder.Default
    private List<StockTransferItemRequest> items = new ArrayList<>();

}