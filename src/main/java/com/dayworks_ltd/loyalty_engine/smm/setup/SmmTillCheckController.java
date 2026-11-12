package com.dayworks_ltd.loyalty_engine.smm.setup;

import com.dayworks_ltd.loyalty_engine.smm.intake.SmmTillCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Safaricom posts the result of our till check payment (Daraja B2B Business Buy Goods) here. Open to the internet like
 * the other M-Pesa callbacks; a result only counts with the one-time token in the path and our ConversationID.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/smm/till-check")
@RequiredArgsConstructor
public class SmmTillCheckController {

    private static final Map<String, Object> ACCEPTED = Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final SmmTillCheckService tillCheck;

    @PostMapping("/result/{token}")
    public ResponseEntity<Map<String, Object>> result(@PathVariable String token, @RequestBody(required = false) Map<String, Object> body) {
        try {
            tillCheck.paymentResult(token, body);
        } catch (Exception e) {
            log.warn("SokoReply: till check result could not be handled: {}", e.getMessage());
        }
        return ResponseEntity.ok(ACCEPTED);
    }

    @PostMapping("/timeout/{token}")
    public ResponseEntity<Map<String, Object>> timeout(@PathVariable String token) {
        try {
            tillCheck.paymentTimedOut(token);
        } catch (Exception e) {
            log.warn("SokoReply: till check timeout could not be handled: {}", e.getMessage());
        }
        return ResponseEntity.ok(ACCEPTED);
    }
}
