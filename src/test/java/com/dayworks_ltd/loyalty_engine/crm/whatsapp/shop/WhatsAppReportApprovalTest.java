package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.campaign.service.Campaign;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.inventory.models.HeldReportSms;
import com.dayworks_ltd.loyalty_engine.inventory.models.ReportRecipient;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.HeldReportSmsRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.ReportRecipientRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Stock count SMS held until the approver says yes on WhatsApp. */
class WhatsAppReportApprovalTest {

    static final String APPROVER_WA = "254708613719";

    final Map<Long, HeldReportSms> held = new HashMap<>();
    HeldReportSmsRepository heldRepo;
    ReportRecipientRepository recipientRepo;
    Campaign campaign;
    WhatsAppCloudClient wa;
    ReportRecipientService recipients;
    WhatsAppReportApproval approval;
    final List<ReportRecipientService.HeldForApproval> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        heldRepo = mock(HeldReportSmsRepository.class);
        doAnswer(inv -> {
            HeldReportSms h = inv.getArgument(0);
            if (h.getId() == null) h.setId((long) held.size() + 1);
            held.put(h.getId(), h);
            return h;
        }).when(heldRepo).save(any());
        when(heldRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(held.get((Long) inv.getArgument(0))));
        when(heldRepo.findByApproverPhoneAndStatusOrderByCreatedAtAsc(anyString(), any())).thenAnswer(inv -> held.values().stream()
                .filter(h -> h.getApproverPhone().equals(inv.getArgument(0)) && h.getStatus() == inv.getArgument(1)).toList());

        recipientRepo = mock(ReportRecipientRepository.class);
        when(recipientRepo.findByMerchantIdOrderByCreatedAtAsc("23")).thenReturn(List.of(
                ReportRecipient.builder().merchantId("23").name("Raymond").phone("0797770952").build()));

        MerchantService merchants = mock(MerchantService.class);
        Merchant asano = new Merchant();
        asano.setBusinessName("ASANO");
        asano.setBusinessPhone("0708613719");
        when(merchants.getMerchantById(anyLong())).thenReturn(Optional.of(asano));

        campaign = mock(Campaign.class);
        when(campaign.sendSMSMessage(any())).thenReturn(true);
        ApplicationEventPublisher publisher = e -> events.add((ReportRecipientService.HeldForApproval) e);
        recipients = new ReportRecipientService(recipientRepo, merchants, campaign, heldRepo, publisher);
        ReflectionTestUtils.setField(recipients, "approvalMerchants", Set.of("23"));

        wa = mock(WhatsAppCloudClient.class);
        when(wa.sendButtons(anyString(), anyString(), anyList())).thenReturn(new WhatsAppCloudClient.Sent(true, "m1", null));
        WhatsAppProperties props = mock(WhatsAppProperties.class);
        when(props.getDisplayNumber()).thenReturn("254700000000");
        approval = new WhatsAppReportApproval(wa, recipients, props);
    }

    private void holdAlert() {
        recipients.sendStockReport("23", ReportRecipientService.ReportType.ALERT, "STOCK ALERT: Tusker 500ml is short by 6");
        events.forEach(approval::onHeld);
        events.clear();
    }

    @Test
    void alertIsHeldAndShownToApproverInsteadOfSent() {
        holdAlert();

        verify(campaign, never()).sendSMSMessage(any());
        assertEquals(HeldReportSms.Status.PENDING, held.get(1L).getStatus());
        assertEquals("0708613719", held.get(1L).getApproverPhone());
        assertTrue(held.get(1L).getShownOnWhatsApp());
        verify(wa).sendButtons(eq(APPROVER_WA), contains("Tusker 500ml is short by 6"), argThat(b ->
                b.get(0).id().equals("AP:OK:1") && b.get(1).id().equals("AP:NO:1")));
    }

    @Test
    void approveButtonSendsTheSmsToEveryone() {
        holdAlert();

        assertTrue(approval.handle(APPROVER_WA, "interactive", "AP:OK:1"));

        assertEquals(HeldReportSms.Status.APPROVED, held.get(1L).getStatus());
        verify(campaign).sendSMSMessage(argThat(sms -> sms.size() == 2));
        verify(wa).sendText(eq(APPROVER_WA), contains("going to 2 numbers"));
    }

    @Test
    void typingApprovedApprovesTheOnlyPendingReport() {
        holdAlert();

        assertTrue(approval.handle(APPROVER_WA, "text", "Approved."));

        assertEquals(HeldReportSms.Status.APPROVED, held.get(1L).getStatus());
        verify(campaign).sendSMSMessage(any());
    }

    @Test
    void dontSendKeepsTheSmsBackAndCannotBeApprovedLater() {
        holdAlert();

        approval.handle(APPROVER_WA, "interactive", "AP:NO:1");
        approval.handle(APPROVER_WA, "interactive", "AP:OK:1");

        assertEquals(HeldReportSms.Status.REJECTED, held.get(1L).getStatus());
        verify(campaign, never()).sendSMSMessage(any());
        verify(wa).sendText(eq(APPROVER_WA), contains("already handled"));
    }

    @Test
    void onlyTheApproverCanApprove() {
        holdAlert();

        assertTrue(approval.handle("254797770952", "interactive", "AP:OK:1"));
        assertFalse(approval.handle("254797770952", "text", "approved"));

        assertEquals(HeldReportSms.Status.PENDING, held.get(1L).getStatus());
        verify(campaign, never()).sendSMSMessage(any());
    }

    @Test
    void severalPendingNeedApproveAll() {
        holdAlert();
        holdAlert();

        approval.handle(APPROVER_WA, "text", "approved");
        verify(campaign, never()).sendSMSMessage(any());
        verify(wa).sendText(eq(APPROVER_WA), contains("approve all"));

        approval.handle(APPROVER_WA, "text", "approve all");
        verify(campaign, times(2)).sendSMSMessage(any());
    }

    @Test
    void whenWhatsAppCannotReachTheApproverTheyGetOneSmsPointer() {
        when(wa.sendButtons(anyString(), anyString(), anyList())).thenReturn(new WhatsAppCloudClient.Sent(false, null, "131047"));

        holdAlert();

        verify(campaign).sendSMSMessage(argThat(sms -> sms.size() == 1 && sms.get(0).key.equals("0708613719")
                && sms.get(0).value.contains("APPROVALS") && !sms.get(0).value.contains("Tusker")));
        assertFalse(held.get(1L).getShownOnWhatsApp());

        // Their next message (about anything) shows the waiting report first, then carries on as normal
        when(wa.sendButtons(anyString(), anyString(), anyList())).thenReturn(new WhatsAppCloudClient.Sent(true, "m2", null));
        assertFalse(approval.handle(APPROVER_WA, "text", "hi"));
        verify(wa, times(2)).sendButtons(eq(APPROVER_WA), contains("Tusker"), anyList());
        assertTrue(held.get(1L).getShownOnWhatsApp());
    }

    @Test
    void shopsNotOnTheListAndRemindersAreSentStraightAway() {
        recipients.sendStockReport("24", ReportRecipientService.ReportType.ALERT, "STOCK ALERT: x");
        recipients.sendStockReport("23", ReportRecipientService.ReportType.REMINDER, "time to count");

        assertTrue(held.isEmpty());
        verify(campaign, times(2)).sendSMSMessage(any());
    }

    @Test
    void ordinaryMessagesPassThrough() {
        assertFalse(approval.handle(APPROVER_WA, "text", "approved"));
        assertFalse(approval.handle(APPROVER_WA, "text", "Tusker 500ml 2"));
    }

    @Test
    void copyNumberSeesTheRecordAndTheDecisionButCannotApprove() {
        ReflectionTestUtils.setField(recipients, "approvalCopyTo", List.of("0797770952", "0708613719"));
        when(wa.sendText(anyString(), anyString())).thenReturn(new WhatsAppCloudClient.Sent(true, "m", null));

        holdAlert();
        verify(wa).sendText(eq("254797770952"), argThat(t -> t.contains("sent to 0708613719 for approval") && t.contains("Tusker")));
        verify(wa, never()).sendText(eq(APPROVER_WA), contains("for approval"));

        approval.handle("254797770952", "interactive", "AP:OK:1");
        assertEquals(HeldReportSms.Status.PENDING, held.get(1L).getStatus());

        approval.handle(APPROVER_WA, "interactive", "AP:OK:1");
        verify(wa).sendText(eq("254797770952"), contains("0708613719 approved"));
    }
}
