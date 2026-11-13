package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Proving a seller owns their till: we pay a few shillings in, they send back the code and amount. */
class SmmTillCheckServiceTest {

    static final String SELLER = "254712000111";

    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    WhatsAppCloudClient whatsapp = mock(WhatsAppCloudClient.class);
    DarajaB2bClient daraja = mock(DarajaB2bClient.class);
    SmmTillCheckService service = new SmmTillCheckService(profiles, whatsapp, daraja, true);

    SmmSellerProfile seller = SmmSellerProfile.builder().id(1L).merchantId(23L).whatsappNumber("0712 000 111").build();
    List<SmmSellerProfile> sellers = List.of(seller);

    @BeforeEach
    void setUp() {
        when(profiles.findAll()).thenReturn(sellers);
        when(profiles.findByMerchantId(23L)).thenReturn(Optional.of(seller));
    }

    /** The seller sent TILL 123456 and Daraja accepted our payment. */
    void automaticPaymentSent() {
        when(daraja.enabled()).thenReturn(true);
        when(daraja.pay(eq("123456"), anyInt(), anyString())).thenReturn("AG_20261112_abc");
        service.tillGiven(SELLER, sellers, "123456");
    }

    static Map<String, Object> result(String conversation, String code, String name) {
        return Map.of("Result", Map.of(
                "ResultType", "0", "ResultCode", "0", "ResultDesc", "The service request is processed successfully",
                "ConversationID", conversation, "TransactionID", code,
                "ResultParameters", Map.of("ResultParameter", List.of(
                        Map.of("Key", "Amount", "Value", "3.00"),
                        Map.of("Key", "ReceiverPartyPublicName", "Value", name)))));
    }

    @Test
    void theTillCommandPaysARandomFewShillingsThroughDaraja() {
        automaticPaymentSent();
        int amount = seller.getTillCheckAmount();
        assertTrue(amount >= 1 && amount <= 9);
        verify(daraja).pay(eq("123456"), eq(amount), eq(seller.getTillCheckToken()));
        assertEquals("AG_20261112_abc", seller.getTillCheckRef());
        assertEquals(1, seller.getTillCheckPayments());
        assertNull(seller.getTillVerifiedAt());
        verify(whatsapp).sendText(eq(SELLER), contains("send us its M-Pesa code and the amount"));
        verify(whatsapp, never()).sendText(any(), contains(String.valueOf(amount) + " shilling"));
    }

    @Test
    void mpesasResultGivesTheCodeAndNameButOnlyWithTheTokenAndConversation() {
        automaticPaymentSent();
        String token = seller.getTillCheckToken();

        service.paymentResult("someone-elses-token", result("AG_20261112_abc", "QK7AB12XYZ", "123456 - FAKE"));
        service.paymentResult(token, result("AG_other", "QK7AB12XYZ", "123456 - FAKE"));
        assertNull(seller.getTillCheckCode(), "a guessed or replayed result changes nothing");

        service.paymentResult(token, result("AG_20261112_abc", "QK7AB12XYZ", "123456 - Jane  W. Enterprises"));
        assertEquals("QK7AB12XYZ", seller.getTillCheckCode());
        assertEquals("JANE W. ENTERPRISES", seller.getTillCheckName());
        assertNull(seller.getTillCheckToken(), "the token works once");
        assertNull(seller.getTillVerifiedAt(), "M-Pesa's answer alone doesn't prove the seller owns the till");
    }

    @Test
    void theRightCodeAndAmountVerifyTheTillWithMpesasName() {
        automaticPaymentSent();
        int amount = seller.getTillCheckAmount();
        service.paymentResult(seller.getTillCheckToken(), result("AG_20261112_abc", "QK7AB12XYZ", "123456 - Jane W. Enterprises"));

        assertTrue(service.answer(SELLER, sellers, "qk7ab12xyz " + amount));
        assertEquals("JANE W. ENTERPRISES", seller.getTillName());
        assertNotNull(seller.getTillVerifiedAt());
        assertTrue(SmmWatermarkService.canWatermark(seller));
        verify(whatsapp).sendText(eq(SELLER), contains("Pay ONLY to Till 123456"));
    }

    @Test
    void aForwardedTillMessageIsReadToo() {
        automaticPaymentSent();
        int amount = seller.getTillCheckAmount();
        service.paymentResult(seller.getTillCheckToken(), result("AG_20261112_abc", "QK7AB12XYZ", "123456 - JANE"));
        String sms = "QK7AB12XYZ Confirmed. Ksh" + amount + ".00 received from TRIBE on 12/11/26 at 3:45 PM. "
                + "New Account balance is Ksh1,234.00.";
        assertTrue(service.answer(SELLER, sellers, sms));
        assertNotNull(seller.getTillVerifiedAt());
    }

    @Test
    void threeWrongAnswersLockTheCheck() {
        automaticPaymentSent();
        int amount = seller.getTillCheckAmount();
        service.paymentResult(seller.getTillCheckToken(), result("AG_20261112_abc", "QK7AB12XYZ", "123456 - JANE"));
        int wrong = amount == 9 ? 8 : amount + 1;

        assertTrue(service.answer(SELLER, sellers, "QK7AB12XYZ " + wrong));
        assertTrue(service.answer(SELLER, sellers, "QK7AB12XY1 " + amount));
        verify(whatsapp).sendText(eq(SELLER), contains("1 try left"));
        assertTrue(service.answer(SELLER, sellers, "QK7AB12XY2 " + amount));
        verify(whatsapp).sendText(eq(SELLER), contains("locked"));

        assertTrue(service.answer(SELLER, sellers, "QK7AB12XYZ " + amount));
        assertNull(seller.getTillVerifiedAt(), "even the right answer is refused once locked");
    }

    @Test
    void anAnswerBeforeMpesaConfirmsIsNotCountedAsWrong() {
        automaticPaymentSent();
        assertTrue(service.answer(SELLER, sellers, "QK7AB12XYZ 3"));
        assertEquals(0, seller.getTillCheckTries());
        verify(whatsapp).sendText(eq(SELLER), contains("still waiting for M-Pesa"));
    }

    @Test
    void theCheckExpiresAfter48Hours() {
        automaticPaymentSent();
        int amount = seller.getTillCheckAmount();
        service.paymentResult(seller.getTillCheckToken(), result("AG_20261112_abc", "QK7AB12XYZ", "123456 - JANE"));
        seller.setTillCheckSentAt(SmmTillCheckService.now().minusHours(49));
        assertTrue(service.answer(SELLER, sellers, "QK7AB12XYZ " + amount));
        assertNull(seller.getTillVerifiedAt());
        verify(whatsapp).sendText(eq(SELLER), contains("expired"));
    }

    @Test
    void aFailedPaymentTellsTheSellerToCheckTheNumber() {
        automaticPaymentSent();
        service.paymentResult(seller.getTillCheckToken(), Map.of("Result", Map.of(
                "ResultCode", "2001", "ResultDesc", "The initiator information is invalid.", "ConversationID", "AG_20261112_abc")));
        assertNull(seller.getTillCheckSentAt());
        verify(whatsapp).sendText(eq("0712 000 111"), contains("couldn't send the payment to till 123456"));
        assertFalse(service.answer(SELLER, sellers, "QK7AB12XYZ 3"), "nothing pending any more");
    }

    @Test
    void withoutDarajaOrWhenItFailsOurTeamPaysByHand() {
        when(daraja.enabled()).thenReturn(false);
        service.tillGiven(SELLER, sellers, "123456");
        verify(daraja, never()).pay(any(), anyInt(), any());
        verify(whatsapp).sendText(eq(SELLER), contains("Our team will send a small payment"));

        when(daraja.enabled()).thenReturn(true);
        when(daraja.pay(any(), anyInt(), any())).thenReturn(null);
        service.tillGiven(SELLER, sellers, "123456");
        assertNull(seller.getTillCheckToken());
        verify(whatsapp, times(2)).sendText(eq(SELLER), contains("Our team will send a small payment"));
    }

    @Test
    void theKillSwitchStopsAutomaticPaymentsEvenWhenDarajaIsConfigured() {
        SmmTillCheckService guarded = new SmmTillCheckService(profiles, whatsapp, daraja, false);
        when(daraja.enabled()).thenReturn(true);
        guarded.tillGiven(SELLER, sellers, "123456");
        verify(daraja, never()).pay(any(), anyInt(), any());
        verify(whatsapp).sendText(eq(SELLER), contains("Our team will send a small payment"));
        verify(whatsapp, never()).sendText(eq(SELLER), contains("We're sending a small payment"));
    }

    @Test
    void aSellerCantKeepAskingForPayments() {
        seller.setTillCheckPayments(SmmTillCheckService.MAX_PAYMENTS);
        when(daraja.enabled()).thenReturn(true);
        service.tillGiven(SELLER, sellers, "123456");
        verify(daraja, never()).pay(any(), anyInt(), any());
        verify(whatsapp).sendText(eq(SELLER), contains("Our team will send a small payment"));
    }

    @Test
    void ourTeamsHandPaymentIsRecordedThenTheSellerProvesIt() {
        assertThrows(IllegalArgumentException.class, () -> service.recordManualPayment(23L, "0712345678", "QK7AB12XYZ", "3", "JANE"));
        assertThrows(IllegalArgumentException.class, () -> service.recordManualPayment(23L, "123456", "12345", "3", "JANE"));
        assertThrows(IllegalArgumentException.class, () -> service.recordManualPayment(23L, "123456", "QK7AB12XYZ", "10", "JANE"));
        assertThrows(IllegalArgumentException.class, () -> service.recordManualPayment(23L, "123456", "QK7AB12XYZ", "3", " "));

        service.recordManualPayment(23L, "123 456", "qk7ab12xyz", "3", "  Jane W. Enterprises ");
        assertNull(seller.getTillVerifiedAt(), "recording our payment is not proof the seller owns the till");
        verify(whatsapp).sendText(eq("0712 000 111"), contains("send us its M-Pesa code and the amount"));

        assertFalse(service.answer(SELLER, sellers, "hello, when is my video ready?"), "other messages route on");
        assertTrue(service.answer(SELLER, sellers, "3 QK7AB12XYZ"));
        assertEquals("JANE W. ENTERPRISES", seller.getTillName());
        assertNotNull(seller.getTillVerifiedAt());
    }

    @Test
    void amountsAndNamesAreReadTheWayMpesaWritesThem() {
        assertEquals(0, new BigDecimal("3").compareTo(SmmTillCheckService.amount(" CONFIRMED. KSH3.00 RECEIVED ON 12/11/26 AT 3:45 PM")));
        assertEquals(0, new BigDecimal("7").compareTo(SmmTillCheckService.amount(" 7")));
        assertNull(SmmTillCheckService.amount(" THANKS"));
        assertEquals("SAF TEST ORG", SmmTillCheckService.cleanName("600000 - saf test org"));
        assertEquals("MAMA MBOGA", SmmTillCheckService.cleanName("Mama  Mboga"));
    }
}
