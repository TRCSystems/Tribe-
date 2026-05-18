package com.dayworks_ltd.loyalty_engine.credit_engine.controller;

import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.CreditScoreResponse;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.DataPeriod;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.MerchantIdRequest;
import com.dayworks_ltd.loyalty_engine.credit_engine.model.CreditScore;
import com.dayworks_ltd.loyalty_engine.credit_engine.service.CreditEngineService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/credit")
@RequiredArgsConstructor
public class CreditScoreController {


    @Autowired
    private UserRepository userRepository;

    private final CreditEngineService creditEngineService;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @PostMapping("/score")
    public ResponseEntity<?> getCreditScore(@RequestBody MerchantIdRequest request) {
        try {
            String userIdStr = request.getMerchantId();
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

            CreditScore scoreEntity = creditEngineService.getCreditScoreForToday(realMerchantId);

            JsonNode breakdown = null;
            if (scoreEntity.getBreakdownJson() != null && !scoreEntity.getBreakdownJson().isBlank()) {
                try {
                    breakdown = objectMapper.readTree(scoreEntity.getBreakdownJson());
                } catch (JsonProcessingException e) {
                    breakdown = objectMapper.createObjectNode()
                            .put("error", "Failed to parse breakdown JSON");
                }
            }

            var response = CreditScoreResponse.builder()
                    .merchant_id(scoreEntity.getMerchantId())
                    .score(scoreEntity.getScore())
                    .grade(scoreEntity.getGrade())
                    .is_provisional(scoreEntity.isProvisional())
                    .calculated_on(scoreEntity.getCalculatedForDate().toString())
                    .data_period(new DataPeriod(
                            scoreEntity.getDataFromDate().toString(),
                            scoreEntity.getDataToDate().toString()
                    ))
                    .breakdown(breakdown)
                    .build();

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
                    "message", "Failed to retrieve credit score: " + e.getMessage()
            ));
        }
    }
}