package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.inventory.models.HeldReportSms;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Stock count alerts and summaries held for approval: the approver sees each one on WhatsApp and the SMS only goes
 * out after they tap Approve (or reply "approved"). If WhatsApp can't reach them (no chat in the last 24 hours) they
 * get one SMS pointing them to WhatsApp, and the report is shown the next time they message.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppReportApproval {

    static final String APPROVE = "AP:OK:";
    static final String REJECT = "AP:NO:";
    static final Set<String> APPROVE_WORDS = Set.of("approved", "approve", "approve it", "yes approve", "approve and send");
    static final Set<String> APPROVE_ALL_WORDS = Set.of("approve all", "approved all", "approve them all");
    static final Set<String> LIST_WORDS = Set.of("approvals", "approval", "pending", "pending reports");
    private static final int MESSAGE_LIMIT = 800;

    private final WhatsAppCloudClient wa;
    private final ReportRecipientService recipients;
    private final WhatsAppProperties props;

    /** Runs once the count that produced the report is saved. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onHeld(ReportRecipientService.HeldForApproval event) {
        try {
            recipients.findHeld(event.heldId()).ifPresent(h -> {
                tellCopies(h, "📋 " + recipients.shopName(h.getMerchantId()) + " stock " + label(h) + ", sent to "
                        + h.getApproverPhone() + " for approval:\n\n" + limit(h.getMessage()));
                boolean shown = ask(h);
                recipients.markShown(h.getId(), shown);
                if (!shown) {
                    String number = props.getDisplayNumber() == null || props.getDisplayNumber().isBlank()
                            ? "" : " (" + props.getDisplayNumber() + ")";
                    recipients.textOne(h.getApproverPhone(), "TRIBE " + recipients.shopName(h.getMerchantId())
                            .replaceAll("[^\\p{L}\\p{N} .'-]", "") + ": a stock " + label(h) + " is waiting for your approval. "
                            + "Send APPROVALS to Tribe on WhatsApp" + number + " to see it. Nothing is sent until you approve.");
                }
            });
        } catch (Exception e) {
            log.error("Could not ask for approval of held report #{}", event.heldId(), e);
        }
    }

    /** Returns true when the message was an approval reply (and so is not for ordering or the count). */
    public boolean handle(String from, String type, String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith(APPROVE) || text.startsWith(REJECT)) {
            decide(from, text.substring(APPROVE.length()), text.startsWith(APPROVE));
            return true;
        }
        List<HeldReportSms> pending = recipients.pendingFor(from);
        if (pending.isEmpty()) return false;

        String lower = text.toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "").trim();
        if ("text".equals(type) && APPROVE_ALL_WORDS.contains(lower)) {
            int sent = 0;
            for (HeldReportSms h : pending) {
                if (recipients.decide(h.getId(), from, true).isPresent()) sent++;
            }
            wa.sendText(from, "✅ Approved " + sent + " report" + (sent == 1 ? "" : "s") + ". The SMS are on their way.");
            return true;
        }
        if ("text".equals(type) && APPROVE_WORDS.contains(lower)) {
            if (pending.size() == 1) {
                decide(from, String.valueOf(pending.get(0).getId()), true);
            } else {
                pending.forEach(h -> recipients.markShown(h.getId(), ask(h)));
                wa.sendText(from, pending.size() + " reports are waiting. Tap Approve on each one, or send *approve all*.");
            }
            return true;
        }
        if ("text".equals(type) && LIST_WORDS.contains(lower)) {
            pending.forEach(h -> recipients.markShown(h.getId(), ask(h)));
            return true;
        }
        // Anything else carries on as normal, but first show reports that never reached WhatsApp
        pending.stream().filter(h -> !Boolean.TRUE.equals(h.getShownOnWhatsApp()))
                .forEach(h -> recipients.markShown(h.getId(), ask(h)));
        return false;
    }

    private void decide(String from, String rawId, boolean approve) {
        long id;
        try {
            id = Long.parseLong(rawId.trim());
        } catch (NumberFormatException e) {
            return;
        }
        Optional<HeldReportSms> decided = recipients.decide(id, from, approve);
        if (decided.isEmpty()) {
            boolean handled = recipients.findHeld(id).filter(h -> h.getStatus() != HeldReportSms.Status.PENDING).isPresent();
            wa.sendText(from, handled ? "That report was already handled." : "That report isn't waiting for your approval.");
            return;
        }
        HeldReportSms h = decided.get();
        if (approve) {
            int n = recipients.recipientCount(h.getMerchantId(), type(h));
            wa.sendText(from, "✅ Approved. The " + label(h) + " SMS is going to " + n + " number" + (n == 1 ? "" : "s") + ".");
        } else {
            wa.sendText(from, "🚫 Not sent. The " + label(h) + " stays in Tribe under Stock Count.");
        }
        tellCopies(h, (approve ? "✅ " : "🚫 ") + h.getApproverPhone() + (approve ? " approved" : " did not approve")
                + " the stock " + label(h) + (approve ? ". The SMS has gone out." : ". No SMS was sent."));
    }

    private void tellCopies(HeldReportSms h, String text) {
        for (String phone : recipients.approvalCopyTo(h.getMerchantId())) {
            WhatsAppCloudClient.Sent sent = wa.sendText(WhatsAppCloudClient.toWaNumber(phone), text);
            if (sent == null || !sent.ok()) log.info("Copy of held report #{} did not reach WhatsApp {}", h.getId(), phone);
        }
    }

    private static String limit(String message) {
        return message.length() > MESSAGE_LIMIT ? message.substring(0, MESSAGE_LIMIT) + "…" : message;
    }

    /** Sends the report with Approve / Don't send buttons. Returns whether WhatsApp accepted it. */
    boolean ask(HeldReportSms h) {
        int n = recipients.recipientCount(h.getMerchantId(), type(h));
        String message = limit(h.getMessage());
        String body = "🔔 " + recipients.shopName(h.getMerchantId()) + ": approve this stock " + label(h) + "?\n\n"
                + message + "\n\nOnce you approve, it goes by SMS to " + n + " number" + (n == 1 ? "" : "s") + ".";
        WhatsAppCloudClient.Sent sent = wa.sendButtons(WhatsAppCloudClient.toWaNumber(h.getApproverPhone()), body, List.of(
                new WhatsAppCloudClient.Button(APPROVE + h.getId(), "Approve & send"),
                new WhatsAppCloudClient.Button(REJECT + h.getId(), "Don't send")));
        if (sent == null || !sent.ok()) {
            log.info("Approval request for held report #{} did not reach WhatsApp: {}", h.getId(), sent == null ? "no reply" : sent.error());
            return false;
        }
        return true;
    }

    private static ReportRecipientService.ReportType type(HeldReportSms h) {
        return ReportRecipientService.ReportType.valueOf(h.getReportType());
    }

    private static String label(HeldReportSms h) {
        return "ALERT".equals(h.getReportType()) ? "alert" : "count summary";
    }
}
