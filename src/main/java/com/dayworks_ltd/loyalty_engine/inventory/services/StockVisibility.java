package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.campaign.service.Campaign;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockViewGrant;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.StockViewGrantRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.utility.Pair;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Blind stock: nobody at the shop sees stock quantities by default — not attendants, and not
 * whoever is using the owner's login on the till. Quantities are only returned when:
 *  - the user is ADMIN (back office), or
 *  - the request carries a valid X-Stock-View-Token, issued after the owner enters an OTP
 *    sent by SMS to the merchant's registered phone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockVisibility {

    public static final String TOKEN_HEADER = "X-Stock-View-Token";
    /** Message used when quantities are requested without owner verification. The frontend matches on it. */
    public static final String LOCKED_MESSAGE = "Owner verification required to view stock quantities";

    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final int MAX_OTP_REQUESTS_PER_15_MIN = 3;

    private final StockViewGrantRepository grantRepository;
    private final MerchantService merchantService;
    private final Campaign campaign;
    private final SecureRandom random = new SecureRandom();

    @Value("${stock-view.otp-validity-minutes:5}")
    private int otpValidityMinutes;

    @Value("${stock-view.grant-minutes:30}")
    private int grantMinutes;

    /** LOCAL TESTING ONLY: return the OTP in the API response instead of relying on SMS. Never enable on a server. */
    @Value("${stock-view.dev-echo-otp:false}")
    private boolean devEchoOtp;

    // ───────────────────────────── visibility checks ─────────────────────────────

    public boolean canSeeQuantities(User user) {
        if (user == null) return false;
        if (user.getRole() == UserRole.ADMIN) return true;
        return activeGrant(user).isPresent();
    }

    public boolean isBlindToStock(User user) {
        return !canSeeQuantities(user);
    }

    /** Returns the items unchanged for verified viewers, or a quantity-free view otherwise. */
    public Object forUser(User user, List<Inventory> items) {
        if (canSeeQuantities(user)) {
            return items;
        }
        return items.stream().map(StockVisibility::blindView).toList();
    }

    public static Map<String, Object> blindView(Inventory item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("itemName", item.getItemName());
        m.put("itemCode", item.getItemCode());
        m.put("unitPrice", item.getUnitPrice());
        m.put("stockStatus", item.getAvailableStock() != null && item.getAvailableStock() > 0 ? "INSTOCK" : "OUTSTOCK");
        return m;
    }

    // ───────────────────────────── owner verification ─────────────────────────────

    /** Sends a 6-digit OTP to the merchant's registered phone. Only MERCHANT logins may ask. */
    @Transactional
    public Map<String, Object> requestOtp(User user) {
        if (user.getRole() != UserRole.MERCHANT) {
            throw new IllegalStateException("Only the shop owner can unlock stock quantities");
        }
        String merchantId = user.getMerchantId();
        if (merchantId == null) {
            throw new IllegalArgumentException("This user is not linked to any merchant");
        }
        if (grantRepository.countByUserIdAndCreatedAtAfter(user.getUserId(), LocalDateTime.now().minusMinutes(15))
                >= MAX_OTP_REQUESTS_PER_15_MIN) {
            throw new IllegalStateException("Too many codes requested. Try again in 15 minutes.");
        }

        Merchant merchant = merchantService.getMerchantById(Long.parseLong(merchantId))
                .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));
        String phone = merchant.getBusinessPhone();

        String otp = String.format("%06d", random.nextInt(1_000_000));
        grantRepository.save(StockViewGrant.builder()
                .userId(user.getUserId())
                .merchantId(merchantId)
                .otpHash(sha256(user.getUserId() + ":" + otp))
                .otpExpiresAt(LocalDateTime.now().plusMinutes(otpValidityMinutes))
                .build());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sentTo", maskPhone(phone));
        result.put("expiresInMinutes", otpValidityMinutes);

        if (devEchoOtp) {
            log.warn("stock-view.dev-echo-otp is ON — OTP returned in the response (local testing only)");
            result.put("devOtp", otp);
        } else {
            if (phone == null || phone.isBlank()) {
                throw new IllegalStateException("No registered phone for this shop. Ask support to add one.");
            }
            ArrayList<Pair<String, String>> sms = new ArrayList<>();
            sms.add(new Pair<>(phone, "Your TRIBE stock view code is " + otp
                    + ". It expires in " + otpValidityMinutes + " minutes. If you did not request it, someone is using your login."));
            if (!campaign.sendSMSMessage(sms)) {
                throw new IllegalStateException("Could not send the code by SMS. Please try again.");
            }
        }
        return result;
    }

    /** Checks the OTP and issues a token valid for grantMinutes. */
    @Transactional
    public Map<String, Object> verifyOtp(User user, String otp) {
        if (otp == null || !otp.trim().matches("\\d{6}")) {
            throw new IllegalArgumentException("Enter the 6-digit code sent to your phone");
        }
        StockViewGrant grant = grantRepository
                .findTopByUserIdAndVerifiedAtIsNullOrderByCreatedAtDesc(user.getUserId())
                .orElseThrow(() -> new IllegalStateException("Request a new code first"));

        if (grant.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("The code has expired. Request a new one.");
        }
        if (grant.getAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new IllegalStateException("Too many wrong codes. Request a new one.");
        }

        boolean match = MessageDigest.isEqual(
                grant.getOtpHash().getBytes(StandardCharsets.UTF_8),
                sha256(user.getUserId() + ":" + otp.trim()).getBytes(StandardCharsets.UTF_8));
        if (!match) {
            grant.setAttempts(grant.getAttempts() + 1);
            grantRepository.save(grant);
            throw new IllegalArgumentException("Wrong code. " + (MAX_OTP_ATTEMPTS - grant.getAttempts()) + " attempt(s) left.");
        }

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(grantMinutes);

        grant.setVerifiedAt(LocalDateTime.now());
        grant.setTokenHash(sha256(token));
        grant.setTokenExpiresAt(expiresAt);
        grantRepository.save(grant);

        return Map.of("token", token, "expiresAt", expiresAt.toString(), "validMinutes", grantMinutes);
    }

    public Map<String, Object> status(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (user.getRole() == UserRole.ADMIN) {
            m.put("unlocked", true);
            m.put("reason", "ADMIN");
            return m;
        }
        Optional<StockViewGrant> grant = activeGrant(user);
        m.put("unlocked", grant.isPresent());
        m.put("canUnlock", user.getRole() == UserRole.MERCHANT);
        grant.ifPresent(g -> m.put("expiresAt", g.getTokenExpiresAt().toString()));
        return m;
    }

    // ───────────────────────────── internals ─────────────────────────────

    private Optional<StockViewGrant> activeGrant(User user) {
        String token = currentToken();
        if (token == null || token.isBlank()) return Optional.empty();
        return grantRepository.findByTokenHash(sha256(token))
                .filter(g -> g.getUserId().equals(user.getUserId()))
                .filter(g -> g.getTokenExpiresAt() != null && g.getTokenExpiresAt().isAfter(LocalDateTime.now()));
    }

    private static String currentToken() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getHeader(TOKEN_HEADER);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "your registered phone";
        return "*".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
