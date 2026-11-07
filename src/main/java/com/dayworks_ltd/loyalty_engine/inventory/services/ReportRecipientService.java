package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.campaign.service.Campaign;
import com.dayworks_ltd.loyalty_engine.inventory.models.HeldReportSms;
import com.dayworks_ltd.loyalty_engine.inventory.models.ReportRecipient;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.HeldReportSmsRepository;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.ReportRecipientRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import com.dayworks_ltd.loyalty_engine.utility.Pair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Who receives stock count SMS for a shop.
 * The registered business phone always receives everything and cannot be removed here;
 * the owner can add up to MAX_EXTRA_RECIPIENTS more numbers. Unlock codes are NOT sent to
 * these numbers — they only ever go to the registered phone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportRecipientService {

    /** SUMMARY / ALERT follow each recipient's choice; REMINDER (time to count) goes to every number. */
    public enum ReportType { SUMMARY, ALERT, REMINDER }

    static final int MAX_EXTRA_RECIPIENTS = 5;

    /** Published after a stock count SMS is held for approval; WhatsApp asks the approver. */
    public record HeldForApproval(Long heldId) {}

    private final ReportRecipientRepository repository;
    private final MerchantService merchantService;
    private final Campaign campaign;
    private final HeldReportSmsRepository heldRepository;
    private final ApplicationEventPublisher events;

    /** Shops whose stock count alerts and summaries wait for a yes on WhatsApp before any SMS goes out. */
    @Value("${reports.sms-approval.merchants:}")
    private Set<String> approvalMerchants = Set.of();

    /** Who approves; empty = the shop's registered phone. */
    @Value("${reports.sms-approval.phone:}")
    private String approvalPhone = "";

    /** Numbers that also see each held report on WhatsApp (they can't approve it). */
    @Value("${reports.sms-approval.copy-to:}")
    private List<String> approvalCopyTo = List.of();

    // ───────────────────────────── management ─────────────────────────────

    public Map<String, Object> list(String merchantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registeredPhone", registeredPhone(merchantId));
        result.put("maxExtra", MAX_EXTRA_RECIPIENTS);
        result.put("recipients", repository.findByMerchantIdOrderByCreatedAtAsc(merchantId).stream()
                .map(ReportRecipientService::view).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> add(String merchantId, String name, String rawPhone,
                                   Boolean receiveSummary, Boolean receiveAlerts, Long byUserId) {
        String phone = normalizePhone(rawPhone);
        String cleanName = cleanName(name);
        if (phone.equals(registeredPhone(merchantId))) {
            throw new IllegalArgumentException("That is the shop's registered phone — it already receives every report");
        }
        if (repository.findByMerchantIdAndPhone(merchantId, phone).isPresent()) {
            throw new IllegalArgumentException(phone + " is already on the list");
        }
        if (repository.countByMerchantId(merchantId) >= MAX_EXTRA_RECIPIENTS) {
            throw new IllegalStateException("You can add up to " + MAX_EXTRA_RECIPIENTS + " extra numbers. Remove one first.");
        }

        ReportRecipient saved = repository.save(ReportRecipient.builder()
                .merchantId(merchantId)
                .name(cleanName)
                .phone(phone)
                .receiveSummary(receiveSummary == null || receiveSummary)
                .receiveAlerts(receiveAlerts == null || receiveAlerts)
                .createdBy(String.valueOf(byUserId))
                .build());

        // Tell the owner, so a number added without their knowledge is noticed
        notifyRegisteredPhone(merchantId, "TRIBE: " + cleanName + " (" + mask(phone)
                + ") was added to your stock report recipients. If this wasn't you, change your password.");
        return view(saved);
    }

    @Transactional
    public Map<String, Object> update(String merchantId, Long id, String name,
                                      Boolean receiveSummary, Boolean receiveAlerts) {
        ReportRecipient r = owned(merchantId, id);
        if (name != null) r.setName(cleanName(name));
        if (receiveSummary != null) r.setReceiveSummary(receiveSummary);
        if (receiveAlerts != null) r.setReceiveAlerts(receiveAlerts);
        r.setUpdatedAt(LocalDateTime.now());
        return view(repository.save(r));
    }

    @Transactional
    public void remove(String merchantId, Long id) {
        ReportRecipient r = owned(merchantId, id);
        repository.delete(r);
        notifyRegisteredPhone(merchantId, "TRIBE: " + r.getName() + " (" + mask(r.getPhone())
                + ") was removed from your stock report recipients.");
    }

    // ───────────────────────────── sending ─────────────────────────────

    /** Sends a stock count SMS to the registered phone plus every recipient subscribed to this type. */
    public void send(String merchantId, ReportType type, String message) {
        try {
            Set<String> phones = new LinkedHashSet<>();
            String registered = registeredPhone(merchantId);
            if (registered != null) phones.add(registered);
            for (ReportRecipient r : repository.findByMerchantIdOrderByCreatedAtAsc(merchantId)) {
                boolean wants = switch (type) {
                    case SUMMARY -> Boolean.TRUE.equals(r.getReceiveSummary());
                    case ALERT -> Boolean.TRUE.equals(r.getReceiveAlerts());
                    case REMINDER -> true;
                };
                if (wants) phones.add(r.getPhone());
            }
            if (phones.isEmpty()) {
                log.warn("Stock count SMS not sent, merchant {} has no phone numbers: {}", merchantId, message);
                return;
            }
            ArrayList<Pair<String, String>> sms = new ArrayList<>();
            phones.forEach(p -> sms.add(new Pair<>(p, message)));
            if (!campaign.sendSMSMessage(sms)) {
                log.warn("Stock count SMS failed for merchant {} ({} recipients)", merchantId, phones.size());
            }
        } catch (Exception e) {
            log.warn("Stock count SMS failed for merchant {}: {}", merchantId, e.getMessage());
        }
    }

    // ───────────────────────────── approval before sending ─────────────────────────────

    /**
     * Stock count alerts and summaries. For shops that need approval the SMS is held and the approver is
     * asked on WhatsApp first (after the current transaction commits); everyone else gets it straight away.
     */
    public void sendStockReport(String merchantId, ReportType type, String message) {
        String approver = type == ReportType.REMINDER ? null : approverFor(merchantId);
        if (approver == null) {
            send(merchantId, type, message);
            return;
        }
        try {
            HeldReportSms held = heldRepository.save(HeldReportSms.builder()
                    .merchantId(merchantId).reportType(type.name()).message(message).approverPhone(approver).build());
            log.info("Stock count {} SMS for merchant {} held for approval by {} (#{})", type, merchantId, approver, held.getId());
            events.publishEvent(new HeldForApproval(held.getId()));
        } catch (Exception e) {
            log.error("Could not hold stock count SMS for merchant {}, not sent: {}", merchantId, message, e);
        }
    }

    /** The approver's 07… number when this shop's reports wait for approval, else null. */
    public String approverFor(String merchantId) {
        if (merchantId == null || !approvalMerchants.contains(merchantId)) return null;
        if (approvalPhone != null && !approvalPhone.isBlank()) {
            try {
                return normalizePhone(approvalPhone);
            } catch (IllegalArgumentException e) {
                log.warn("reports.sms-approval.phone is not a valid number, using the registered phone");
            }
        }
        return registeredPhone(merchantId);
    }

    /** 07… numbers that get a copy of a held report (never the approver), for shops that need approval. */
    public List<String> approvalCopyTo(String merchantId) {
        String approver = approverFor(merchantId);
        if (approver == null) return List.of();
        Set<String> phones = new LinkedHashSet<>();
        for (String raw : approvalCopyTo) {
            try {
                String phone = normalizePhone(raw);
                if (!phone.equals(approver)) phones.add(phone);
            } catch (IllegalArgumentException e) {
                log.warn("reports.sms-approval.copy-to has an invalid number, skipped");
            }
        }
        return new ArrayList<>(phones);
    }

    public Optional<HeldReportSms> findHeld(Long id) {
        return heldRepository.findById(id);
    }

    /** Reports still waiting for this number's decision, oldest first. */
    public List<HeldReportSms> pendingFor(String rawPhone) {
        String phone;
        try {
            phone = normalizePhone(rawPhone);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return heldRepository.findByApproverPhoneAndStatusOrderByCreatedAtAsc(phone, HeldReportSms.Status.PENDING);
    }

    /** Own transaction: called after the count's transaction has committed, where a joined save would be lost. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markShown(Long heldId, boolean shown) {
        heldRepository.findById(heldId).ifPresent(h -> {
            h.setShownOnWhatsApp(shown);
            heldRepository.save(h);
        });
    }

    /**
     * Approves (sends the SMS) or rejects a held report. Only its approver can decide, and only once.
     * Returns the report as decided, or empty when it isn't theirs or was already decided.
     */
    @Transactional
    public Optional<HeldReportSms> decide(Long heldId, String rawPhone, boolean approve) {
        String phone;
        try {
            phone = normalizePhone(rawPhone);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<HeldReportSms> found = heldRepository.findById(heldId)
                .filter(h -> h.getApproverPhone().equals(phone) && h.getStatus() == HeldReportSms.Status.PENDING);
        if (found.isEmpty()) return Optional.empty();
        HeldReportSms h = found.get();
        h.setStatus(approve ? HeldReportSms.Status.APPROVED : HeldReportSms.Status.REJECTED);
        h.setDecidedAt(LocalDateTime.now());
        h.setDecidedBy("WhatsApp " + phone);
        HeldReportSms saved = heldRepository.save(h);
        if (approve) send(h.getMerchantId(), ReportType.valueOf(h.getReportType()), h.getMessage());
        log.info("Held stock count SMS #{} for merchant {} {} by {}", heldId, h.getMerchantId(),
                approve ? "approved and sent" : "rejected", phone);
        return Optional.of(saved);
    }

    /** How many numbers an approved report of this type goes to. */
    public int recipientCount(String merchantId, ReportType type) {
        Set<String> phones = new HashSet<>();
        String registered = registeredPhone(merchantId);
        if (registered != null) phones.add(registered);
        for (ReportRecipient r : repository.findByMerchantIdOrderByCreatedAtAsc(merchantId)) {
            if (type == ReportType.SUMMARY ? Boolean.TRUE.equals(r.getReceiveSummary()) : Boolean.TRUE.equals(r.getReceiveAlerts())) {
                phones.add(r.getPhone());
            }
        }
        return phones.size();
    }

    /** One SMS to one number (used to point the approver to WhatsApp when WhatsApp can't reach them). */
    public void textOne(String phone, String message) {
        ArrayList<Pair<String, String>> sms = new ArrayList<>();
        sms.add(new Pair<>(phone, message));
        try {
            campaign.sendSMSMessage(sms);
        } catch (Exception e) {
            log.warn("SMS to {} failed: {}", mask(phone), e.getMessage());
        }
    }

    /**
     * Whether this number may do the shop's stock count on WhatsApp: the registered business phone or one of
     * the numbers the owner added here. Accepts WhatsApp's 2547… form.
     */
    public boolean isStaffNumber(String merchantId, String rawPhone) {
        String phone;
        try {
            phone = normalizePhone(rawPhone);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return phone.equals(registeredPhone(merchantId)) || repository.findByMerchantIdAndPhone(merchantId, phone).isPresent();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    public String shopName(String merchantId) {
        try {
            return merchantService.getMerchantById(Long.parseLong(merchantId))
                    .map(Merchant::getBusinessName).orElse("your shop");
        } catch (NumberFormatException e) {
            return "your shop";
        }
    }

    /** Accepts 07.., 01.., 7.., 1.., 254.., +254.. and returns 07XXXXXXXX / 01XXXXXXXX. */
    public static String normalizePhone(String raw) {
        if (raw == null) throw new IllegalArgumentException("Phone number is required");
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("254")) digits = "0" + digits.substring(3);
        else if (digits.length() == 9 && (digits.startsWith("7") || digits.startsWith("1"))) digits = "0" + digits;
        if (!digits.matches("0[17]\\d{8}")) {
            throw new IllegalArgumentException("Enter a valid Kenyan mobile number, e.g. 0712345678");
        }
        return digits;
    }

    /** The shop's registered business phone as 07…, or null. */
    public String registeredPhoneOf(String merchantId) {
        return registeredPhone(merchantId);
    }

    private String registeredPhone(String merchantId) {
        try {
            String phone = merchantService.getMerchantById(Long.parseLong(merchantId))
                    .map(Merchant::getBusinessPhone).orElse(null);
            if (phone == null || phone.isBlank()) return null;
            try {
                return normalizePhone(phone);
            } catch (IllegalArgumentException e) {
                return phone.trim();
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void notifyRegisteredPhone(String merchantId, String message) {
        String phone = registeredPhone(merchantId);
        if (phone == null) return;
        ArrayList<Pair<String, String>> sms = new ArrayList<>();
        sms.add(new Pair<>(phone, message));
        try {
            campaign.sendSMSMessage(sms);
        } catch (Exception e) {
            log.warn("Recipient-change notice failed for merchant {}: {}", merchantId, e.getMessage());
        }
    }

    private ReportRecipient owned(String merchantId, Long id) {
        return repository.findById(id)
                .filter(r -> r.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));
    }

    /** The SMS client builds JSON by hand, so keep names to plain characters. */
    private static String cleanName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} .'-]", "").trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException("Name is required");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String mask(String phone) {
        return phone.length() <= 3 ? phone : "*".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
    }

    private static Map<String, Object> view(ReportRecipient r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("phone", r.getPhone());
        m.put("receiveSummary", r.getReceiveSummary());
        m.put("receiveAlerts", r.getReceiveAlerts());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
