package com.dayworks_ltd.loyalty_engine.merchants;

import com.dayworks_ltd.loyalty_engine.auth.DTO.UserDto;
import com.dayworks_ltd.loyalty_engine.auth.enums.Status;
import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.services.UserService;
import com.dayworks_ltd.loyalty_engine.common.ApiResponseBody;
import com.dayworks_ltd.loyalty_engine.dto.CreateMerchantRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;



@Slf4j
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;
    private final UserService userService;


    public MerchantController(MerchantService merchantService, UserService userService) {
        this.merchantService = merchantService;
        this.userService = userService;
    }


    @PostMapping("/createMerchant")
    public ResponseEntity<ApiResponseBody> createMerchant(@Valid @RequestBody CreateMerchantRequest request) {
        try {
            Merchant created = merchantService.createMerchant(request);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponseBody.builder()
                            .status("201")
                            .message("success")
                            .respObject(created)
                            .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponseBody.builder()
                            .status("400")
                            .message("Invalid request")  // don't expose e.getMessage()
                            .build());
        } catch (Exception e) {
            log.error("Failed to create merchant: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponseBody.builder()
                            .status("500")
                            .message("An unexpected error occurred") // don't expose e.getMessage()
                            .build());
        }
    }

    @GetMapping
    public ResponseEntity<List<Merchant>> getAllMerchants() {
        return ResponseEntity.ok(merchantService.getAllMerchants());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getMerchantById(@PathVariable Long id) {
        return merchantService.getMerchantById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/till/{tillNumber}")
    public ResponseEntity<Merchant> getMerchantByTill(@PathVariable String tillNumber) {
        return merchantService.getMerchantByTillNumber(tillNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Merchant> updateMerchant(@PathVariable Long id, @Valid @RequestBody Merchant merchant) {
        return ResponseEntity.ok(merchantService.updateMerchant(id, merchant));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMerchant(@PathVariable Long id) {
        merchantService.deleteMerchant(id);
        return ResponseEntity.noContent().build();
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
}
