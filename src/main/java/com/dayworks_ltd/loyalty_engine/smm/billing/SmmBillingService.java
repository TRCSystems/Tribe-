package com.dayworks_ltd.loyalty_engine.smm.billing;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmPlan;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSubscription;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SokoReply's monthly plan and its M-Pesa STK push billing, using the same payment microservice
 * (and the same initiate/check-stk-push-status calls) that Fresh Mart's order checkout uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmBillingService {

    private final SmmSubscriptionRepository subscriptions;
    private final RestTemplate restTemplate;

    @Value("${payment.base-url}")
    private String paymentBaseUrl;

    /** The three tiers, for rendering the billing/pricing page. */
    public List<Map<String, Object>> plans() {
        return List.of(planInfo(SmmPlan.STARTER), planInfo(SmmPlan.GROWTH, true), planInfo(SmmPlan.PRO));
    }

    private Map<String, Object> planInfo(SmmPlan plan) {
        return planInfo(plan, false);
    }

    private Map<String, Object> planInfo(SmmPlan plan, boolean recommended) {
        return Map.of(
                "plan", plan.name(),
                "priceKes", plan.priceKes(),
                "maxPlatforms", plan.maxPlatforms(),
                "platformChoiceRequired", plan == SmmPlan.STARTER,
                "crossPlatformPosting", plan.crossPlatformPosting(),
                "stockArrivalAlerts", plan.stockArrivalAlerts(),
                "scamContactHiding", plan.scamContactHiding(),
                "recommended", recommended);
    }

    public Optional<SmmSubscription> subscriptionFor(Long merchantId) {
        return subscriptions.findByMerchantId(merchantId);
    }

    /** True once the merchant has an unexpired, paid-for subscription. */
    public boolean isActive(Long merchantId) {
        return subscriptionFor(merchantId).map(SmmSubscription::isActive).orElse(false);
    }

    /**
     * Whether the merchant may connect an account on this platform right now: needs an active subscription,
     * and - on Starter - it must be the seller's one chosen platform. {@code alreadyConnectedPlatforms} is the
     * distinct platform types the merchant already has an ACTIVE account on; adding another account of a
     * platform they already have never costs more room under the plan's platform-type limit.
     */
    public boolean canConnectPlatform(Long merchantId, SocialPlatform platform, Set<SocialPlatform> alreadyConnectedPlatforms) {
        SmmSubscription sub = subscriptionFor(merchantId).orElse(null);
        if (sub == null || !sub.isActive()) return false;
        if (sub.getPlan() == SmmPlan.STARTER && platform != sub.getStarterPlatform()) return false;
        if (alreadyConnectedPlatforms.contains(platform)) return true;
        return alreadyConnectedPlatforms.size() < sub.getPlan().maxPlatforms();
    }

    /** Starts (or restarts) a subscription: records the chosen plan and sends an STK push for its price. */
    @Transactional
    public SmmSubscription subscribe(Long merchantId, SmmPlan plan, String phoneNumber, SocialPlatform starterPlatform) {
        if (plan == SmmPlan.STARTER) {
            if (starterPlatform == null || !SmmPlan.STARTER_CHOICES.contains(starterPlatform)) {
                throw new IllegalArgumentException("Starter needs exactly one platform: Facebook or Instagram");
            }
        } else {
            starterPlatform = null;
        }

        SmmSubscription sub = subscriptionFor(merchantId).orElseGet(() -> SmmSubscription.builder().merchantId(merchantId).build());
        sub.setPlan(plan);
        sub.setStarterPlatform(starterPlatform);
        sub.setStatus(SmmSubscription.Status.PENDING_PAYMENT);

        String checkoutRequestId = initiateStkPush(normalizeMsisdn(phoneNumber), plan.priceKes());
        sub.setCheckoutRequestId(checkoutRequestId);
        return subscriptions.save(sub);
    }

    /** Checks the pending STK push; activates the plan for 30 days from now if M-Pesa confirms payment. */
    @Transactional
    public SmmSubscription confirmPayment(Long merchantId) {
        SmmSubscription sub = subscriptionFor(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("No subscription found for this merchant"));
        if (sub.getCheckoutRequestId() == null) {
            throw new IllegalArgumentException("No payment is pending for this merchant");
        }

        Map<String, Object> confirmRequest = Map.of("paymentReference", sub.getCheckoutRequestId());
        ResponseEntity<Map> response = restTemplate.postForEntity(
                paymentBaseUrl + "/api/v1/payment/check-stk-push-status", confirmRequest, Map.class);

        if (!isPaymentSuccessful(response.getBody())) {
            log.info("SokoReply billing: payment not yet confirmed for merchant {}", merchantId);
            return sub;
        }

        String mpesaReceipt = receiptFrom(response.getBody());
        sub.setStatus(SmmSubscription.Status.ACTIVE);
        sub.setLastPaymentReference(mpesaReceipt != null ? mpesaReceipt : sub.getCheckoutRequestId());
        sub.setLastPaymentAt(LocalDateTime.now());
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusDays(30));
        sub.setCheckoutRequestId(null);
        log.info("SokoReply billing: merchant {} activated on {} until {}", merchantId, sub.getPlan(), sub.getCurrentPeriodEnd());
        return subscriptions.save(sub);
    }

    private String initiateStkPush(String phoneNumber, java.math.BigDecimal amount) {
        Map<String, Object> stkRequest = Map.of("phoneNumber", phoneNumber, "amount", amount);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                paymentBaseUrl + "/api/v1/payment/initiate-stk-push", stkRequest, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Could not start the M-Pesa payment");
        }
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        String checkoutRequestId = data == null ? null : (String) data.get("paymentReference");
        if (checkoutRequestId == null || checkoutRequestId.isBlank()) {
            throw new RuntimeException("M-Pesa did not return a payment reference");
        }
        return checkoutRequestId;
    }

    @SuppressWarnings("unchecked")
    private boolean isPaymentSuccessful(Map<String, Object> body) {
        if (body == null) return false;
        // The envelope's "status" is an int 200; inside "data", ResultCode comes back as the STRING "0" on
        // success — unlike ResponseCode, which is a number and whose success value isn't reliably "0" once
        // JSON-deserialized (e.g. 0.0), so ResultCode is what Fresh Mart's own order checkout matches on.
        Object statusObj = body.get("status");
        if (!(statusObj instanceof Number) || ((Number) statusObj).intValue() != 200) return false;
        Object dataObj = body.get("data");
        if (!(dataObj instanceof Map)) return false;
        Object resultCode = ((Map<String, Object>) dataObj).get("ResultCode");
        return resultCode != null && "0".equals(resultCode.toString());
    }

    @SuppressWarnings("unchecked")
    private String receiptFrom(Map<String, Object> body) {
        if (body == null) return null;
        Object dataObj = body.get("data");
        if (!(dataObj instanceof Map)) return null;
        Object receipt = ((Map<String, Object>) dataObj).get("MpesaReceipt");
        return receipt == null ? null : receipt.toString().trim();
    }

    private static String normalizeMsisdn(String phone) {
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("Phone number is required");
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) return "254" + digits.substring(1);
        if (digits.startsWith("254") && digits.length() == 12) return digits;
        if ((digits.startsWith("7") || digits.startsWith("1")) && digits.length() == 9) return "254" + digits;
        throw new IllegalArgumentException("Invalid phone number format: " + phone);
    }

    /** Once a day: subscriptions whose paid period has lapsed stop being active. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 1 * * *", zone = "Africa/Nairobi")
    @Transactional
    public void expireLapsedSubscriptions() {
        List<SmmSubscription> lapsed = subscriptions.findByStatusAndCurrentPeriodEndBefore(
                SmmSubscription.Status.ACTIVE, LocalDateTime.now());
        for (SmmSubscription sub : lapsed) {
            sub.setStatus(SmmSubscription.Status.EXPIRED);
            subscriptions.save(sub);
            log.info("SokoReply billing: merchant {}'s {} plan expired", sub.getMerchantId(), sub.getPlan());
        }
    }
}
