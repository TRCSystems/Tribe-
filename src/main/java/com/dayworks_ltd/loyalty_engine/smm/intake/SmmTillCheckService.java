package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proves a seller owns the till they gave us before their videos say "Pay ONLY to Till …".
 * <ol>
 *   <li>The seller sends {@code TILL 123456} on WhatsApp.</li>
 *   <li>We pay a random KES 1-9 into it: automatically through Daraja B2B Business Buy Goods, or by hand with our
 *       team recording the code, amount and name (the admin endpoint).</li>
 *   <li>M-Pesa tells us the code and the till's public name; the seller's own till message shows the same code.</li>
 *   <li>The seller sends back the code and amount (or forwards the till's message). A match verifies the till and
 *       takes the name from M-Pesa, never from the seller. Three wrong answers lock it; the check lasts 48 hours.</li>
 * </ol>
 * A screenshot or a pasted confirmation from the seller's own payment proves nothing (anyone can pay any till); only
 * the till's owner can see the code of a payment we made.
 */
@Slf4j
@Service
public class SmmTillCheckService {

    static final int MAX_TRIES = 3;
    static final int MAX_PAYMENTS = 3;
    static final int HOURS_VALID = 48;
    /** An M-Pesa code: 10 letters and digits, with at least one of each. */
    static final Pattern CODE = Pattern.compile("\\b(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\\d)[A-Z0-9]{10}\\b");
    static final Pattern KSH = Pattern.compile("(?:KSH|KES|SH)\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)");
    static final Pattern NUMBER = Pattern.compile("(?<![\\w.,/:])(\\d{1,4}(?:\\.\\d{1,2})?)(?![\\w,/:])");
    /** "600000 - SAF TEST ORG" → "SAF TEST ORG". */
    static final Pattern NAME_PREFIX = Pattern.compile("^\\s*\\d+\\s*[-–]\\s*");

    private final SmmSellerProfileRepository profiles;
    private final WhatsAppCloudClient whatsapp;
    private final DarajaB2bClient daraja;
    private final Random random = new SecureRandom();

    public SmmTillCheckService(SmmSellerProfileRepository profiles, WhatsAppCloudClient whatsapp, DarajaB2bClient daraja) {
        this.profiles = profiles;
        this.whatsapp = whatsapp;
        this.daraja = daraja;
    }

    static LocalDateTime now() {
        return LocalDateTime.now(SmmIntakeService.NAIROBI);
    }

    static boolean pending(SmmSellerProfile p) {
        return p.getTillVerifiedAt() == null && p.getTillCheckSentAt() != null && p.getTillNumber() != null;
    }

    // ───────────────────────────── 1. the seller gives the till ─────────────────────────────

    /** The seller's {@code TILL 123456}: reset the till and pay into it (or tell them our team will). */
    public void tillGiven(String from, List<SmmSellerProfile> sellers, String till) {
        if (sellers.stream().allMatch(p -> till.equals(p.getTillNumber()) && p.getTillVerifiedAt() != null)) {
            whatsapp.sendText(from, "Till " + till + " is already verified ✅ (" + sellers.get(0).getTillName()
                    + "). Send us a reel as a Document and we'll add it.");
            return;
        }
        int paymentsSoFar = sellers.stream().mapToInt(p -> nz(p.getTillCheckPayments())).max().orElse(0);
        boolean automatic = daraja.enabled() && paymentsSoFar < MAX_PAYMENTS;
        int amount = 1 + random.nextInt(9);
        String token = automatic ? UUID.randomUUID().toString().replace("-", "") : null;
        for (SmmSellerProfile p : sellers) {
            p.setTillNumber(till);
            p.setTillName(null);
            p.setTillVerifiedAt(null);
            p.setTillCheckAmount(automatic ? amount : null);
            p.setTillCheckCode(null);
            p.setTillCheckName(null);
            p.setTillCheckTries(0);
            p.setTillCheckSentAt(now());
            p.setTillCheckRef(null);
            p.setTillCheckToken(token);
        }
        profiles.saveAll(sellers);
        List<Long> merchants = sellers.stream().map(SmmSellerProfile::getMerchantId).toList();

        if (automatic) {
            String ref = daraja.pay(till, amount, token);
            if (ref != null) {
                for (SmmSellerProfile p : sellers) {
                    p.setTillCheckRef(ref);
                    p.setTillCheckPayments(nz(p.getTillCheckPayments()) + 1);
                }
                profiles.saveAll(sellers);
                log.info("SokoReply: till check payment to {} for merchant(s) {} sent to M-Pesa ({})", till, merchants, ref);
                whatsapp.sendText(from, "Thanks ✅ We're sending a small payment (a few shillings) to till " + till
                        + " now, to prove the till is yours.\n\nWhen it arrives on the till, send us its M-Pesa code and "
                        + "the amount, like this: QK7AB12XYZ 3\n\nWe'll never ask for your PIN.");
                return;
            }
            for (SmmSellerProfile p : sellers) p.setTillCheckToken(null);
            profiles.saveAll(sellers);
            log.warn("SokoReply: automatic till check to {} for merchant(s) {} failed; our team pays by hand", till, merchants);
        } else if (daraja.enabled()) {
            log.warn("SokoReply: merchant(s) {} reached {} automatic till payments; our team pays by hand", merchants, MAX_PAYMENTS);
        } else {
            log.info("SokoReply: till {} given by merchant(s) {}; waiting for our team's test payment", till, merchants);
        }
        whatsapp.sendText(from, "Thanks ✅ Our team will send a small payment (a few shillings) to till " + till
                + ", to prove the till is yours.\n\nWhen it arrives on the till, send us its M-Pesa code and the amount, "
                + "like this: QK7AB12XYZ 3\n\nWe'll never ask for your PIN.");
    }

    // ───────────────────────────── 2. what M-Pesa says about our payment ─────────────────────────────

    /** Our team paid by hand: record the code, amount and the name M-Pesa showed, and ask the seller for the code. */
    public SmmSellerProfile recordManualPayment(Long merchantId, String tillNumber, String mpesaCode, String amount, String tillName) {
        SmmSellerProfile p = profiles.findByMerchantId(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("No SokoReply profile for merchant " + merchantId));
        String till = tillNumber == null ? "" : tillNumber.replaceAll("\\s", "");
        if (!till.matches("\\d{5,7}")) throw new IllegalArgumentException("A till number has 5 to 7 digits");
        String code = mpesaCode == null ? "" : mpesaCode.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("The M-Pesa code is 10 letters and digits, e.g. QK7AB12XYZ");
        int shillings;
        try {
            shillings = new BigDecimal(amount == null ? "" : amount.trim()).intValueExact();
        } catch (Exception e) {
            throw new IllegalArgumentException("Give the amount in whole shillings, 1 to 9");
        }
        if (shillings < 1 || shillings > 9) throw new IllegalArgumentException("Pay a random amount from 1 to 9 shillings");
        String name = cleanName(tillName);
        if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("Give the name exactly as M-Pesa showed it (up to 60 characters)");

        p.setTillNumber(till);
        p.setTillName(null);
        p.setTillVerifiedAt(null);
        p.setTillCheckAmount(shillings);
        p.setTillCheckCode(code);
        p.setTillCheckName(name);
        p.setTillCheckTries(0);
        p.setTillCheckSentAt(now());
        p.setTillCheckRef(null);
        p.setTillCheckToken(null);
        profiles.save(p);
        log.info("SokoReply: manual till check payment to {} recorded for merchant {}", till, merchantId);
        if (p.getWhatsappNumber() != null) {
            whatsapp.sendText(p.getWhatsappNumber(), "We've sent a small payment to till " + till + ". To prove the till "
                    + "is yours, send us its M-Pesa code and the amount from the till's message, like this: QK7AB12XYZ 3"
                    + "\n\nWe'll never ask for your PIN.");
        }
        return p;
    }

    /** Daraja's result for our payment. Only accepted with the one-time token and the matching ConversationID. */
    @SuppressWarnings("unchecked")
    public void paymentResult(String token, Map<String, Object> body) {
        if (token == null || token.isBlank()) return;
        List<SmmSellerProfile> sellers = profiles.findAll().stream().filter(p -> token.equals(p.getTillCheckToken())).toList();
        Map<String, Object> result = body != null && body.get("Result") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String conversation = str(result.get("ConversationID"));
        String originator = str(result.get("OriginatorConversationID"));
        sellers = sellers.stream().filter(p -> p.getTillCheckRef() != null
                && (p.getTillCheckRef().equals(conversation) || p.getTillCheckRef().equals(originator))).toList();
        if (sellers.isEmpty()) {
            log.warn("SokoReply: till check result with an unknown token or conversation ignored");
            return;
        }
        SmmSellerProfile first = sellers.get(0);
        String code = str(result.get("ResultCode"));
        if (!"0".equals(code)) {
            for (SmmSellerProfile p : sellers) {
                p.setTillCheckToken(null);
                p.setTillCheckSentAt(null);
            }
            profiles.saveAll(sellers);
            log.warn("SokoReply: till check payment to {} failed: {} {}", first.getTillNumber(), code, str(result.get("ResultDesc")));
            send(first, "We couldn't send the payment to till " + first.getTillNumber() + " (M-Pesa said: "
                    + str(result.get("ResultDesc")) + "). Please check the number and send it again, like this: TILL 123456");
            return;
        }
        Map<String, String> params = parameters(result);
        String mpesaCode = str(result.get("TransactionID")).toUpperCase(Locale.ROOT);
        String name = cleanName(params.get("ReceiverPartyPublicName"));
        for (SmmSellerProfile p : sellers) {
            p.setTillCheckCode(mpesaCode);
            p.setTillCheckName(name.isEmpty() ? null : name);
            p.setTillCheckToken(null);
        }
        profiles.saveAll(sellers);
        log.info("SokoReply: till check payment to {} confirmed by M-Pesa", first.getTillNumber());
        if (name.isEmpty()) log.warn("SokoReply: M-Pesa gave no public name for till {}; our team must check it", first.getTillNumber());
    }

    /** Daraja gave up on our payment before it ran. */
    public void paymentTimedOut(String token) {
        if (token == null || token.isBlank()) return;
        List<SmmSellerProfile> sellers = profiles.findAll().stream().filter(p -> token.equals(p.getTillCheckToken())).toList();
        if (sellers.isEmpty()) return;
        for (SmmSellerProfile p : sellers) {
            p.setTillCheckToken(null);
            p.setTillCheckSentAt(null);
        }
        profiles.saveAll(sellers);
        log.warn("SokoReply: till check payment to {} timed out at M-Pesa", sellers.get(0).getTillNumber());
        send(sellers.get(0), "M-Pesa was too busy to send our payment to till " + sellers.get(0).getTillNumber()
                + ". Please send TILL " + sellers.get(0).getTillNumber() + " again in a few minutes.");
    }

    // ───────────────────────────── 3. the seller sends the code back ─────────────────────────────

    /** True when this text was the seller's answer to a till check (right, wrong or early). */
    public boolean answer(String from, List<SmmSellerProfile> sellers, String text) {
        List<SmmSellerProfile> open = sellers.stream().filter(SmmTillCheckService::pending).toList();
        if (open.isEmpty() || text == null) return false;
        String upper = text.toUpperCase(Locale.ROOT);
        Matcher c = CODE.matcher(upper);
        if (!c.find()) return false;
        String given = c.group();
        BigDecimal amount = amount(upper.substring(0, c.start()) + " " + upper.substring(c.end()));

        SmmSellerProfile p = open.get(0);
        String till = p.getTillNumber();
        if (p.getTillCheckSentAt().isBefore(now().minusHours(HOURS_VALID))) {
            whatsapp.sendText(from, "That check has expired. Send TILL " + till + " to start again.");
            return true;
        }
        if (nz(p.getTillCheckTries()) >= MAX_TRIES) {
            whatsapp.sendText(from, "This till check is locked after " + MAX_TRIES + " wrong answers. Our team will call you to finish it.");
            return true;
        }
        if (p.getTillCheckCode() == null || p.getTillCheckAmount() == null) {
            whatsapp.sendText(from, "We're still waiting for M-Pesa to confirm our payment. Please send the code again in a few minutes.");
            return true;
        }
        if (amount == null) {
            whatsapp.sendText(from, "Please send the amount too, like this: " + given + " 3");
            return true;
        }
        boolean match = given.equals(p.getTillCheckCode()) && amount.compareTo(BigDecimal.valueOf(p.getTillCheckAmount())) == 0;
        if (!match) {
            int tries = nz(p.getTillCheckTries()) + 1;
            for (SmmSellerProfile s : open) s.setTillCheckTries(tries);
            profiles.saveAll(open);
            log.info("SokoReply: wrong till check answer {} of {} for till {}", tries, MAX_TRIES, till);
            if (tries >= MAX_TRIES) {
                log.warn("SokoReply: till check for {} locked after {} wrong answers (merchant(s) {})", till, MAX_TRIES,
                        open.stream().map(SmmSellerProfile::getMerchantId).toList());
                whatsapp.sendText(from, "That doesn't match our payment either, so the check is locked. Our team will call you to finish it.");
            } else {
                whatsapp.sendText(from, "That doesn't match our payment. Check the code and amount on the till's own M-Pesa "
                        + "message (not a payment you made) and try again. " + (MAX_TRIES - tries) + " tr"
                        + (MAX_TRIES - tries == 1 ? "y" : "ies") + " left.");
            }
            return true;
        }
        if (p.getTillCheckName() == null) {
            log.warn("SokoReply: till {} matched but M-Pesa gave no name; our team records it", till);
            whatsapp.sendText(from, "✅ That matches. Our team is confirming the exact name M-Pesa shows for till " + till
                    + "; we'll tell you here when your videos are ready.");
            return true;
        }
        for (SmmSellerProfile s : open) {
            s.setTillName(s.getTillCheckName().toUpperCase(Locale.ROOT));
            s.setTillVerifiedAt(now());
        }
        profiles.saveAll(open);
        log.info("SokoReply: till {} verified for merchant(s) {}", till, open.stream().map(SmmSellerProfile::getMerchantId).toList());
        whatsapp.sendText(from, "✅ Your till is verified: " + till + " · " + p.getTillName() + ".\n\n"
                + "Send us any reel as a Document (📎 → Document) and we'll send it back with \"Pay ONLY to Till "
                + till + "\" on it. Post that one, so even a copied video points buyers to you.");
        return true;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** The amount in "QK7AB12XYZ 3", "3 QK7AB12XYZ" or a forwarded "…Confirmed. Ksh3.00 received from…". */
    static BigDecimal amount(String rest) {
        Matcher k = KSH.matcher(rest);
        String s = k.find() ? k.group(1) : null;
        if (s == null) {
            Matcher n = NUMBER.matcher(rest);
            s = n.find() ? n.group(1) : null;
        }
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String cleanName(String name) {
        if (name == null) return "";
        return NAME_PREFIX.matcher(name).replaceFirst("").trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> parameters(Map<String, Object> result) {
        Map<String, String> out = new HashMap<>();
        if (!(result.get("ResultParameters") instanceof Map<?, ?> rp)) return out;
        Object list = rp.get("ResultParameter");
        List<Object> items = list instanceof List<?> l ? (List<Object>) l : list == null ? List.of() : List.of(list);
        for (Object o : items) {
            if (o instanceof Map<?, ?> kv && kv.get("Key") != null) out.put(kv.get("Key").toString(), str(kv.get("Value")));
        }
        return out;
    }

    private void send(SmmSellerProfile p, String text) {
        if (p.getWhatsappNumber() != null) whatsapp.sendText(p.getWhatsappNumber(), text);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
