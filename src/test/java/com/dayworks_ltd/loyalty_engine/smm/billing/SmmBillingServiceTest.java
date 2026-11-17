package com.dayworks_ltd.loyalty_engine.smm.billing;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmPayment;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmPlan;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSubscription;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmPaymentRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSubscriptionRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Picking a plan, paying for it by M-Pesa STK push, and the entitlement checks that gate connecting platforms. */
class SmmBillingServiceTest {

    static final Long MERCHANT = 23L;

    SmmSubscriptionRepository subscriptions = mock(SmmSubscriptionRepository.class);
    SmmPaymentRepository payments = mock(SmmPaymentRepository.class);
    RestTemplate restTemplate = mock(RestTemplate.class);
    SmmBillingService billing = new SmmBillingService(subscriptions, payments, restTemplate);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(billing, "paymentBaseUrl", "http://payments.local");
        when(subscriptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void subscribingToStarterWithoutAPlatformChoiceFails() {
        assertThrows(IllegalArgumentException.class, () -> billing.subscribe(MERCHANT, SmmPlan.STARTER, "0712345678", null));
    }

    @Test
    void subscribingSendsAnStkPushForThePlansPrice() {
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.empty());
        mockStkPushInitiate("ws_CO_123");

        SmmSubscription sub = billing.subscribe(MERCHANT, SmmPlan.GROWTH, "0712345678", null);

        assertEquals(SmmPlan.GROWTH, sub.getPlan());
        assertEquals(SmmSubscription.Status.PENDING_PAYMENT, sub.getStatus());
        assertEquals("ws_CO_123", sub.getCheckoutRequestId());
        verify(restTemplate).postForEntity(eq("http://payments.local/api/v1/payment/initiate-stk-push"),
                eq(Map.of("phoneNumber", "254712345678", "amount", new BigDecimal("1500"))), eq(Map.class));
    }

    @Test
    void confirmingASuccessfulPaymentActivatesThePlanFor30Days() {
        SmmSubscription sub = SmmSubscription.builder().id(1L).merchantId(MERCHANT).plan(SmmPlan.PRO)
                .status(SmmSubscription.Status.PENDING_PAYMENT).checkoutRequestId("ws_CO_123").build();
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));
        when(restTemplate.postForEntity(eq("http://payments.local/api/v1/payment/check-stk-push-status"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", 200, "message", "Success",
                        "data", Map.of("ResultCode", "0", "ResponseCode", 0.0, "MpesaReceipt", "QAB123XYZ"))));

        SmmSubscription result = billing.confirmPayment(MERCHANT);

        assertEquals(SmmSubscription.Status.ACTIVE, result.getStatus());
        assertEquals("QAB123XYZ", result.getLastPaymentReference());
        assertNull(result.getCheckoutRequestId());
        assertTrue(result.getCurrentPeriodEnd().isAfter(LocalDateTime.now().plusDays(29)));
        assertTrue(result.isActive());

        ArgumentCaptor<SmmPayment> recorded = ArgumentCaptor.forClass(SmmPayment.class);
        verify(payments).save(recorded.capture());
        assertEquals(MERCHANT, recorded.getValue().getMerchantId());
        assertEquals(SmmPlan.PRO, recorded.getValue().getPlan());
        assertEquals(0, new BigDecimal("3500").compareTo(recorded.getValue().getAmountKes()));
        assertEquals("QAB123XYZ", recorded.getValue().getMpesaReceipt());
        assertEquals(result.getCurrentPeriodEnd(), recorded.getValue().getPeriodEnd());
    }

    @Test
    void aReceiptThatLandsJustAfterTheSuccessfulResultIsStillCaptured() {
        SmmSubscription sub = SmmSubscription.builder().id(1L).merchantId(MERCHANT).plan(SmmPlan.STARTER)
                .starterPlatform(SocialPlatform.FACEBOOK).status(SmmSubscription.Status.PENDING_PAYMENT).checkoutRequestId("ws_CO_123").build();
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));
        Map<String, Object> noReceiptYet = new java.util.HashMap<>();
        noReceiptYet.put("ResultCode", "0");
        noReceiptYet.put("MpesaReceipt", null);
        when(restTemplate.postForEntity(eq("http://payments.local/api/v1/payment/check-stk-push-status"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", 200, "data", noReceiptYet)))
                .thenReturn(ResponseEntity.ok(Map.of("status", 200, "data", Map.of("ResultCode", "0", "MpesaReceipt", "LATE123"))));

        SmmSubscription result = billing.confirmPayment(MERCHANT);

        assertEquals(SmmSubscription.Status.ACTIVE, result.getStatus());
        assertEquals("LATE123", result.getLastPaymentReference());
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(Map.class));

        ArgumentCaptor<SmmPayment> recorded = ArgumentCaptor.forClass(SmmPayment.class);
        verify(payments).save(recorded.capture());
        assertEquals("LATE123", recorded.getValue().getMpesaReceipt());
    }

    @Test
    void aReceiptThatNeverArrivesStillActivatesUsingTheCheckoutIdAsTheReference() {
        SmmSubscription sub = SmmSubscription.builder().id(1L).merchantId(MERCHANT).plan(SmmPlan.STARTER)
                .starterPlatform(SocialPlatform.FACEBOOK).status(SmmSubscription.Status.PENDING_PAYMENT).checkoutRequestId("ws_CO_123").build();
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));
        Map<String, Object> noReceipt = new java.util.HashMap<>();
        noReceipt.put("ResultCode", "0");
        noReceipt.put("MpesaReceipt", null);
        when(restTemplate.postForEntity(eq("http://payments.local/api/v1/payment/check-stk-push-status"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", 200, "data", noReceipt)));

        SmmSubscription result = billing.confirmPayment(MERCHANT);

        assertEquals(SmmSubscription.Status.ACTIVE, result.getStatus());
        assertEquals("ws_CO_123", result.getLastPaymentReference());
        verify(restTemplate, times(3)).postForEntity(anyString(), any(), eq(Map.class));

        ArgumentCaptor<SmmPayment> recorded = ArgumentCaptor.forClass(SmmPayment.class);
        verify(payments).save(recorded.capture());
        assertNull(recorded.getValue().getMpesaReceipt());
    }

    @Test
    void confirmingBeforeMpesaConfirmsLeavesItPending() {
        SmmSubscription sub = SmmSubscription.builder().id(1L).merchantId(MERCHANT).plan(SmmPlan.PRO)
                .status(SmmSubscription.Status.PENDING_PAYMENT).checkoutRequestId("ws_CO_123").build();
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));
        Map<String, Object> dataWithNullResultCode = new java.util.HashMap<>();
        dataWithNullResultCode.put("ResponseCode", -1.0);
        dataWithNullResultCode.put("ResultCode", null);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", 200, "message", "processing", "data", dataWithNullResultCode)));

        SmmSubscription result = billing.confirmPayment(MERCHANT);

        assertEquals(SmmSubscription.Status.PENDING_PAYMENT, result.getStatus());
        assertFalse(result.isActive());
        verifyNoInteractions(payments);
    }

    @Test
    void starterCanOnlyConnectItsOneChosenPlatform() {
        SmmSubscription sub = active(SmmPlan.STARTER, SocialPlatform.FACEBOOK);
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));

        assertTrue(billing.canConnectPlatform(MERCHANT, SocialPlatform.FACEBOOK, Set.of()));
        assertFalse(billing.canConnectPlatform(MERCHANT, SocialPlatform.INSTAGRAM, Set.of()));
    }

    @Test
    void growthAllowsAllThreePlatformsButNotAFourth() {
        SmmSubscription sub = active(SmmPlan.GROWTH, null);
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));

        assertTrue(billing.canConnectPlatform(MERCHANT, SocialPlatform.FACEBOOK, Set.of()));
        assertTrue(billing.canConnectPlatform(MERCHANT, SocialPlatform.TIKTOK,
                Set.of(SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM)));
        // Already at the 3-platform cap: a second Facebook Page is fine (same platform), a new one of a 4th type isn't possible anyway
        assertTrue(billing.canConnectPlatform(MERCHANT, SocialPlatform.FACEBOOK,
                Set.of(SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM, SocialPlatform.TIKTOK)));
    }

    @Test
    void noSubscriptionMeansNoConnecting() {
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.empty());
        assertFalse(billing.canConnectPlatform(MERCHANT, SocialPlatform.FACEBOOK, Set.of()));
    }

    @Test
    void anExpiredSubscriptionMeansNoConnecting() {
        SmmSubscription sub = SmmSubscription.builder().merchantId(MERCHANT).plan(SmmPlan.GROWTH)
                .status(SmmSubscription.Status.EXPIRED).currentPeriodEnd(LocalDateTime.now().minusDays(1)).build();
        when(subscriptions.findByMerchantId(MERCHANT)).thenReturn(Optional.of(sub));
        assertFalse(billing.canConnectPlatform(MERCHANT, SocialPlatform.FACEBOOK, Set.of()));
    }

    private SmmSubscription active(SmmPlan plan, SocialPlatform starterPlatform) {
        return SmmSubscription.builder().merchantId(MERCHANT).plan(plan).starterPlatform(starterPlatform)
                .status(SmmSubscription.Status.ACTIVE).currentPeriodEnd(LocalDateTime.now().plusDays(10)).build();
    }

    private void mockStkPushInitiate(String checkoutRequestId) {
        when(restTemplate.postForEntity(eq("http://payments.local/api/v1/payment/initiate-stk-push"), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("data", Map.of("paymentReference", checkoutRequestId))));
    }
}
