package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmContact;
import com.dayworks_ltd.loyalty_engine.crm.models.CrmEnums;
import com.dayworks_ltd.loyalty_engine.crm.repositories.CrmContactRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Contact details and marketing consent per distributor + shop. */
@Service
@RequiredArgsConstructor
public class CrmContactService {

    private final CrmContactRepository repository;
    private final MerchantService merchantService;

    /** Returns the contact, creating it from the shop's business phone on first use. */
    @Transactional
    public CrmContact getOrCreate(Long distributorId, Long shopId) {
        return repository.findByDistributorIdAndShopMerchantId(distributorId, shopId)
                .orElseGet(() -> repository.save(CrmContact.builder()
                        .distributorId(distributorId)
                        .shopMerchantId(shopId)
                        .phone(validPhoneOrNull(merchantService.getMerchantById(shopId)
                                .map(Merchant::getBusinessPhone).orElse(null)))
                        .build()));
    }

    @Transactional
    public CrmContact update(Long distributorId, Long shopId, String phone, String whatsappNumber,
                             CrmEnums.Channel preferredChannel, Boolean marketingOptOut) {
        CrmContact c = getOrCreate(distributorId, shopId);
        if (phone != null) {
            c.setPhone(phone.isBlank() ? null : ReportRecipientService.normalizePhone(phone));
        }
        if (whatsappNumber != null) {
            c.setWhatsappNumber(whatsappNumber.isBlank() ? null : ReportRecipientService.normalizePhone(whatsappNumber));
        }
        if (preferredChannel != null) {
            c.setPreferredChannel(preferredChannel);
        }
        if (marketingOptOut != null && !marketingOptOut.equals(c.getMarketingOptOut())) {
            c.setMarketingOptOut(marketingOptOut);
            c.setOptOutAt(marketingOptOut ? LocalDateTime.now() : null);
        }
        c.setUpdatedAt(LocalDateTime.now());
        return repository.save(c);
    }

    /** The contact for this distributor + shop, without creating one. */
    public java.util.Optional<CrmContact> find(Long distributorId, Long shopId) {
        return repository.findByDistributorIdAndShopMerchantId(distributorId, shopId);
    }

    /** Records who to talk to at the shop, and the WhatsApp number they order from. */
    @Transactional
    public CrmContact setContactPerson(Long distributorId, Long shopId, String contactName, String whatsappNumber) {
        CrmContact c = getOrCreate(distributorId, shopId);
        if (contactName != null && !contactName.isBlank()) {
            String name = contactName.trim();
            c.setContactName(name.length() > 120 ? name.substring(0, 120) : name);
        }
        String wa = validPhoneOrNull(whatsappNumber);
        if (wa != null && !wa.equals(c.getPhone())) c.setWhatsappNumber(wa);
        if (c.getPhone() == null && wa != null) c.setPhone(wa);
        c.setUpdatedAt(LocalDateTime.now());
        return repository.save(c);
    }

    /**
     * STOP from a phone number: opts that number out of marketing for every distributor that has it.
     * Ready for an inbound SMS / WhatsApp webhook; also usable manually.
     */
    @Transactional
    public int optOutByPhone(String rawPhone) {
        String phone = ReportRecipientService.normalizePhone(rawPhone);
        List<CrmContact> contacts = repository.findByPhoneOrWhatsappNumber(phone, phone);
        contacts.forEach(c -> {
            c.setMarketingOptOut(true);
            c.setOptOutAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
        });
        repository.saveAll(contacts);
        return contacts.size();
    }

    /** Every distributor contact that uses this number (SMS or WhatsApp). */
    public List<CrmContact> findByNumber(String rawPhone) {
        String phone = validPhoneOrNull(rawPhone);
        return phone == null ? List.of() : repository.findByPhoneOrWhatsappNumber(phone, phone);
    }

    /**
     * The shop messaged our WhatsApp number: it's on WhatsApp and has opted in to chat, so prefer
     * WhatsApp for it and open the 24-hour window for free-form replies.
     */
    @Transactional
    public List<CrmContact> recordWhatsappInbound(String rawPhone) {
        List<CrmContact> contacts = findByNumber(rawPhone);
        LocalDateTime now = LocalDateTime.now();
        contacts.forEach(c -> {
            c.setLastWhatsappInboundAt(now);
            c.setPreferredChannel(CrmEnums.Channel.WHATSAPP);
            c.setUpdatedAt(now);
        });
        return repository.saveAll(contacts);
    }

    /** WhatsApp said the number isn't on WhatsApp: go back to SMS for it. */
    @Transactional
    public void fallBackToSms(String rawPhone) {
        List<CrmContact> contacts = findByNumber(rawPhone);
        contacts.forEach(c -> {
            c.setPreferredChannel(CrmEnums.Channel.SMS);
            c.setUpdatedAt(LocalDateTime.now());
        });
        repository.saveAll(contacts);
    }

    private static String validPhoneOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ReportRecipientService.normalizePhone(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
