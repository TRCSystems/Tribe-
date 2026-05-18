package com.dayworks_ltd.loyalty_engine.inventory.controller;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.inventory.DTO.StockTransferRequest;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockTransfer;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockTransferService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/stock-transfers")
@RequiredArgsConstructor
public class StockTransferController {

    private final StockTransferService stockTransferService;
    @Autowired
    private UserRepository userRepository;
    Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Distributor creates a new stock transfer (Manual Pickup or fulfilling an order)
     */
    @PostMapping("/create")
    @Operation(summary = "Distributor creates stock transfer to a merchant")
    public ResponseEntity<?> createStockTransfer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody StockTransferRequest request) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.getUserById(userId);

            if (user == null || user.getMerchantId() == null || user.getMerchantId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "User is not linked to any merchant"
                ));
            }

            // Override whatever merchantId the client sent in the request body
            // Never trust client-supplied identity — derive it from the auth token
            request.setDistributorId(Long.parseLong(user.getMerchantId()));

            StockTransfer transfer = stockTransferService.createStockTransfer(request, userId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "statusCode", 200,
                    "message", "Stock transfer created successfully",
                    "transferCode", transfer.getTransferCode()
                    // NOTE: Do NOT return transfer entity directly — add a DTO
            ));
        } catch (Exception e) {
            logger.error("Error creating stock transfer", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to create stock transfer"
            ));
        }
    }

    @PostMapping("/{transferId}/confirm")
    @Operation(summary = "Merchant confirms receipt of stock")
    public ResponseEntity<?> confirmReceipt(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long transferId,
            @RequestBody(required = false) Map<String, String> body) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.getUserById(userId);

            if (user == null || user.getMerchantId() == null || user.getMerchantId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "User is not linked to any merchant"
                ));
            }
            String merchantId = user.getMerchantId();

            String notes = (body != null) ? body.getOrDefault("notes", null) : null;

            StockTransfer transfer = stockTransferService.confirmReceipt(transferId, userId, notes,merchantId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "statusCode", 200,
                    "message", "Stock receipt confirmed successfully",
                    "transferCode", transfer.getTransferCode()
            ));
        } catch (Exception e) {
            logger.error("Error confirming stock receipt for transfer {}", transferId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to confirm stock receipt"
            ));
        }
    }
    /**
     * Merchant gets his pending stock receipts (things he needs to confirm)
     */
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingReceipts(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.getUserById(userId);

            if (user == null || user.getMerchantId() == null || user.getMerchantId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "User is not linked to any merchant"
                ));
            }

            String merchantId = user.getMerchantId();
            List<StockTransfer> pending = stockTransferService.getPendingReceipts(merchantId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "statusCode", 200,
                    "count", pending.size(),
                    "data", pending
            ));
        } catch (Exception e) {
            logger.error("Error fetching pending receipts", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve pending receipts"
            ));
        }
    }

    @GetMapping("/my-issued")
    public ResponseEntity<?> getMyIssuedTransfers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(401).body(Map.of("status", "ERROR", "message", "Unauthorized"));
        }

        try {
            Long userId = userDetails.getUserId();
            User user = userRepository.getUserById(userId);

            if (user == null || user.getMerchantId() == null || user.getMerchantId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILURE",
                        "statusCode", 400,
                        "message", "User is not linked to any merchant"
                ));
            }

            String distributorId = user.getMerchantId();
            List<StockTransfer> transfers = stockTransferService.getTransfersByDistributor(distributorId);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "statusCode", 200,
                    "data", transfers
            ));
        } catch (Exception e) {
            logger.error("Error fetching issued transfers", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "statusCode", 500,
                    "message", "Failed to retrieve issued transfers"
            ));
        }
    }
}