package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockReceipt;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockReceiptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adding stock on WhatsApp from a supplier receipt. A staff number sends "stock" (or a receipt photo captioned
 * "stock"/"receipt"), the photo is read, the lines are read back for fixing, and the receipt is sent to the shop's
 * approver. Stock only goes up when the approver taps Add stock.
 */
@Slf4j
@Service
public class WhatsAppStockReceipt {

    static final long IDLE_MINUTES = 60;
    static final Set<String> START_WORDS = Set.of("stock", "add stock", "stock in", "restock", "receipt", "receipts",
            "delivery", "new stock", "received stock", "stock received", "invoice", "supplier receipt", "ongeza stock");
    private static final Set<String> CAPTION_WORDS = Set.of("stock", "receipt", "restock", "delivery", "invoice", "risiti");
    private static final Set<String> STOP_WORDS = Set.of("stop", "exit", "quit", "back", "cancel", "menu", "order", "shop");
    private static final Set<String> SEND_WORDS = Set.of("ok", "okay", "send", "yes", "ndio", "sawa", "correct", "confirm");
    private static final Set<String> DISCARD_WORDS = Set.of("discard", "no", "hapana", "wrong", "clear");
    static final String APPROVE = "RS:OK:";
    static final String REJECT = "RS:NO:";
    private static final Pattern QTY = Pattern.compile("^(\\d{1,2}) (?:is |ni |to )?(\\d{1,6})$");
    /** Matched against the text as typed (not normalized), so "1,200.50" keeps its separators. */
    private static final Pattern COST = Pattern.compile("^(\\d{1,2})\\s+(?:cost|price|bei|at|@|kes|ksh)\\s*(?:kes|ksh|=)?\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,7})(\\.\\d{1,2})?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEW = Pattern.compile("^(\\d{1,2}) new$");
    private static final Pattern REMOVE = Pattern.compile("^(?:remove|delete|ondoa) (\\d{1,2})$");
    private static final Pattern MATCH = Pattern.compile("^(\\d{1,2})\\s*(?:is|=|ni)\\s+(.*[a-zA-Z].*)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_UNITS = 100_000;

    private final WhatsAppCloudClient wa;
    private final StockReceiptService receipts;
    private final ReportRecipientService recipients;
    private final ShoppingListReader reader;
    private final InventoryRepository inventoryRepository;
    private final MediaArchive mediaArchive;
    private final WhatsAppProperties props;

    public WhatsAppStockReceipt(WhatsAppCloudClient wa, StockReceiptService receipts, ReportRecipientService recipients,
                                ShoppingListReader reader, InventoryRepository inventoryRepository, MediaArchive mediaArchive,
                                WhatsAppProperties props) {
        this.wa = wa;
        this.receipts = receipts;
        this.recipients = recipients;
        this.reader = reader;
        this.inventoryRepository = inventoryRepository;
        this.mediaArchive = mediaArchive;
        this.props = props;
    }

    enum Kind { EXISTING, NEW, UNSURE }

    /** One receipt line as read back. units null = quantity couldn't be read. */
    static final class Draft {
        String itemCode;
        String name;
        String written;
        String packs;
        Integer units;
        BigDecimal unitCost;
        Kind kind;
        List<String> options = List.of();
        boolean check;
    }

    static final class Session {
        final String merchantId;
        final List<Draft> drafts = new ArrayList<>();
        String supplier;
        String number;
        String date;
        BigDecimal total;
        String photoPath;
        volatile LocalDateTime touched = LocalDateTime.now();

        Session(String merchantId) {
            this.merchantId = merchantId;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // ───────────────────────────── staff side ─────────────────────────────

    /** Takes the message when the number is adding stock or asks to; false leaves it to ordering. */
    public boolean handle(String from, String merchantId, ShoppingListReader.Vertical vertical, String type,
                          String content, Map<String, Object> message) {
        String text = content == null ? "" : content.trim();
        String lower = ShopAssistant.normalize(text);
        boolean tapped = "interactive".equals(type) || "button".equals(type);
        Session s = active(from, merchantId);
        boolean start = "RC:START".equals(text) || ("text".equals(type) && START_WORDS.contains(lower));
        boolean captioned = "image".equals(type) && captionAsksForStock(message);
        if (s == null && tapped && text.startsWith("RC:")) {
            if (!"RC:STOP".equals(text)) wa.sendText(from, "That receipt timed out. Send *stock* and the photo again.");
            return true;
        }
        if (s == null && !start && !captioned) return false;
        // Customers send receipts as orders too: a captioned photo is only stock-in from a staff number
        if (s == null && !start && !recipients.isStaffNumber(merchantId, from)) return false;
        if (s != null && ((tapped && !text.startsWith("RC:"))
                || ("text".equals(type) && WhatsAppStockCount.START_WORDS.contains(lower)))) {
            sessions.remove(from);
            return false;
        }
        if (!recipients.isStaffNumber(merchantId, from)) {
            sessions.remove(from);
            wa.sendText(from, "Adding stock on WhatsApp is only for the shop's staff numbers. Ask the owner to add your number "
                    + "in Tribe under Stock count → Report recipients.");
            return true;
        }
        if (start) {
            sessions.put(from, new Session(merchantId));
            wa.sendButtons(from, "📥 Add stock, " + recipients.shopName(merchantId) + "\n\n"
                    + "Send a clear photo of the supplier receipt or invoice: the whole page, flat, in good light.\n\n"
                    + "I'll read it back for you to check, then " + approverLabel(merchantId)
                    + " approves it before the stock is added.", List.of(new WhatsAppCloudClient.Button("RC:STOP", "Cancel")));
            return true;
        }
        if (s == null) {
            s = new Session(merchantId);
            sessions.put(from, s);
        }
        s.touched = LocalDateTime.now();

        if ("image".equals(type)) {
            photo(from, s, vertical, message);
            return true;
        }
        if ("RC:STOP".equals(text) || STOP_WORDS.contains(lower)) {
            sessions.remove(from);
            wa.sendText(from, "Cancelled, no stock was added.");
            return true;
        }
        if (s.drafts.isEmpty()) {
            wa.sendText(from, "Send a photo of the supplier receipt, or *stop* to cancel.");
            return true;
        }
        if ("RC:SEND".equals(text) || SEND_WORDS.contains(lower)) {
            send(from, s);
            return true;
        }
        if ("RC:DISCARD".equals(text) || DISCARD_WORDS.contains(lower)) {
            sessions.remove(from);
            wa.sendText(from, "Discarded, no stock was added. Send *stock* to start again.");
            return true;
        }
        fix(from, s, text, lower);
        return true;
    }

    private Session active(String from, String merchantId) {
        Session s = sessions.get(from);
        if (s == null) return null;
        if (!s.merchantId.equals(merchantId) || s.touched.isBefore(LocalDateTime.now().minusMinutes(IDLE_MINUTES))) {
            sessions.remove(from);
            return null;
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static boolean captionAsksForStock(Map<String, Object> message) {
        if (message == null || !(message.get("image") instanceof Map<?, ?> m) || m.get("caption") == null) return false;
        String caption = ShopAssistant.normalize(m.get("caption").toString());
        return Arrays.stream(caption.split(" ")).anyMatch(CAPTION_WORDS::contains);
    }

    @SuppressWarnings("unchecked")
    private void photo(String from, Session s, ShoppingListReader.Vertical vertical, Map<String, Object> message) {
        if (!reader.isConfigured()) {
            wa.sendText(from, "Reading receipts isn't switched on yet. Please add the stock in Tribe.");
            return;
        }
        Map<String, Object> image = message != null && message.get("image") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        WhatsAppCloudClient.Media media = wa.downloadMedia(image.get("id") == null ? null : image.get("id").toString());
        if (media == null) {
            wa.sendText(from, "Sorry, I couldn't open that photo. Please send it again.");
            return;
        }
        Path saved = mediaArchive.save(media.bytes(), media.mimeType(), "receipt", from);
        wa.sendText(from, "📷 Got it, reading the receipt…");
        List<Inventory> stock = inventoryRepository.findByMerchantId(s.merchantId).stream()
                .filter(i -> !Boolean.FALSE.equals(i.getIsActive())).toList();
        ShoppingListReader.ReceiptReading reading;
        try {
            String caption = image.get("caption") == null ? null : image.get("caption").toString();
            reading = reader.readReceipt(media.bytes(), media.mimeType(), caption, stock, vertical);
        } catch (Exception e) {
            log.warn("Receipt photo from {} could not be read: {}", from, e.getMessage());
            wa.sendText(from, "Sorry, I couldn't read that receipt. Try a clearer photo of the whole page in good light.");
            return;
        }
        if (reading == null || !reading.receipt() || reading.lines() == null || reading.lines().isEmpty()) {
            wa.sendText(from, "That doesn't look like a supplier receipt I can read. Send a clear photo of the whole receipt or invoice.");
            return;
        }

        Map<String, Inventory> byCode = new HashMap<>();
        stock.forEach(i -> byCode.put(i.getItemCode(), i));
        s.drafts.clear();
        s.supplier = reading.supplierName();
        s.number = reading.receiptNumber();
        s.date = reading.receiptDate();
        s.total = reading.total() == null ? null : BigDecimal.valueOf(reading.total());
        s.photoPath = saved == null ? null : saved.toString();
        for (ShoppingListReader.ReceiptLine l : reading.lines()) {
            Draft d = new Draft();
            d.written = l.written();
            d.packs = l.packs();
            d.units = l.units() == null || l.units() <= 0 || l.units() > MAX_UNITS ? null : l.units();
            d.unitCost = l.unitCost() == null || l.unitCost() <= 0 ? null
                    : BigDecimal.valueOf(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            if (d.unitCost == null && l.lineTotal() != null && d.units != null) {
                d.unitCost = BigDecimal.valueOf(l.lineTotal()).divide(BigDecimal.valueOf(d.units), 2, RoundingMode.HALF_UP);
            }
            Inventory match = l.itemCode() == null ? null : byCode.get(l.itemCode());
            if (match != null) {
                d.kind = Kind.EXISTING;
                d.itemCode = match.getItemCode();
                d.name = match.getItemName();
                d.check = !"high".equalsIgnoreCase(l.confidence());
            } else if ("none".equalsIgnoreCase(l.confidence())) {
                d.kind = Kind.NEW;
                d.name = cleanName(l.newItemName() != null && !l.newItemName().isBlank() ? l.newItemName() : l.written());
            } else {
                d.kind = Kind.UNSURE;
                d.name = l.written();
                d.options = l.alternatives() == null ? List.of() : l.alternatives().stream()
                        .map(byCode::get).filter(Objects::nonNull).map(Inventory::getItemCode).distinct().limit(5).toList();
            }
            s.drafts.add(d);
        }
        readBack(from, s);
    }

    private void readBack(String from, Session s) {
        Map<String, String> names = names(s.merchantId);
        StringBuilder sb = new StringBuilder("📥 Receipt");
        if (s.supplier != null) sb.append(" from ").append(s.supplier);
        if (s.number != null) sb.append(" · No. ").append(s.number);
        if (s.date != null) sb.append(" · ").append(s.date);
        sb.append("\n\n");
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < s.drafts.size(); i++) {
            Draft d = s.drafts.get(i);
            sb.append(i + 1).append(". ");
            if (d.kind == Kind.NEW) sb.append("🆕 ");
            if (d.kind == Kind.UNSURE || d.units == null) sb.append("❓ ");
            else if (d.check) sb.append("⚠️ ");
            sb.append(d.name).append(" ").append(d.units == null ? "quantity unclear" : "+" + d.units);
            if (d.packs != null && !d.packs.isBlank() && d.units != null && !d.packs.trim().replaceAll("^(\\d+).*$", "$1").equals(String.valueOf(d.units))) {
                sb.append(" (").append(d.packs.trim()).append(")");
            }
            if (d.unitCost != null) sb.append(" @ KES ").append(kes(d.unitCost));
            if (d.kind == Kind.NEW) sb.append(", new product");
            if (d.kind == Kind.UNSURE) {
                sb.append(d.options.isEmpty() ? ", which product?"
                        : ", which one? " + String.join(" / ", d.options.stream().map(c -> names.getOrDefault(c, c)).toList()));
            }
            sb.append('\n');
            if (d.units != null && d.unitCost != null) sum = sum.add(d.unitCost.multiply(BigDecimal.valueOf(d.units)));
        }
        if (s.total != null) {
            sb.append("\nReceipt total KES ").append(kes(s.total));
            if (sum.subtract(s.total).abs().compareTo(BigDecimal.valueOf(Math.max(5, s.total.doubleValue() * 0.01))) > 0) {
                sb.append(" ⚠️ the lines add up to KES ").append(kes(sum)).append(", please check");
            }
            sb.append('\n');
        }
        List<StockReceipt> earlier = receipts.earlierWithNumber(s.merchantId, s.number);
        if (!earlier.isEmpty()) {
            sb.append("\n⚠️ Receipt No. ").append(s.number).append(" was already sent on ")
                    .append(earlier.get(0).getCreatedAt().toLocalDate()).append(". Sending it again adds the stock twice.\n");
        }
        sb.append("\nFix a line by replying, e.g. *2 = 36* (quantity), *2 cost 210*, *3 is Kane Extra 1/4*, *3 new*, *remove 4*.");
        String body = sb.toString();
        List<WhatsAppCloudClient.Button> buttons = List.of(new WhatsAppCloudClient.Button("RC:SEND", "Send for approval"),
                new WhatsAppCloudClient.Button("RC:DISCARD", "Discard"));
        if (body.length() <= 1000) {
            wa.sendButtons(from, body, buttons);
        } else {
            wa.sendText(from, body);
            wa.sendButtons(from, "Is the receipt right?", buttons);
        }
    }

    private void fix(String from, Session s, String text, String lower) {
        Matcher m;
        if ((m = COST.matcher(text)).matches()) {
            Draft d = line(from, s, m.group(1));
            if (d == null) return;
            d.unitCost = new BigDecimal(m.group(2).replace(",", "") + (m.group(3) == null ? "" : m.group(3)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else if ((m = QTY.matcher(lower)).matches()) {
            Draft d = line(from, s, m.group(1));
            if (d == null) return;
            int units = Integer.parseInt(m.group(2));
            if (units == 0) {
                s.drafts.remove(d);
            } else if (units > MAX_UNITS) {
                wa.sendText(from, "That's more than I can add in one go. Check the number.");
                return;
            } else {
                d.units = units;
                d.check = false;
            }
        } else if ((m = NEW.matcher(lower)).matches()) {
            Draft d = line(from, s, m.group(1));
            if (d == null) return;
            d.kind = Kind.NEW;
            d.itemCode = null;
            d.name = cleanName(d.written == null ? d.name : d.written);
        } else if ((m = REMOVE.matcher(lower)).matches()) {
            Draft d = line(from, s, m.group(1));
            if (d == null) return;
            s.drafts.remove(d);
        } else if ((m = MATCH.matcher(text)).matches()) {
            Draft d = line(from, s, m.group(1));
            if (d == null) return;
            List<Inventory> found = find(s.merchantId, m.group(2), d.options);
            if (found.size() != 1) {
                wa.sendText(from, found.isEmpty()
                        ? "I can't find \"" + m.group(2).trim() + "\" in your stock list. Reply *" + m.group(1) + " new* to add it as a new product."
                        : "That fits " + found.size() + " products: " + String.join(", ", found.stream().limit(6).map(Inventory::getItemName).toList())
                        + ". Reply with the full name and size.");
                return;
            }
            d.kind = Kind.EXISTING;
            d.itemCode = found.get(0).getItemCode();
            d.name = found.get(0).getItemName();
            d.check = false;
        } else {
            wa.sendText(from, "Fix a line like *2 = 36* (quantity), *2 cost 210*, *3 is Kane Extra 1/4*, *3 new* or *remove 4*. "
                    + "Tap *Send for approval* when it's right, or send *stop* to cancel.");
            return;
        }
        if (s.drafts.isEmpty()) {
            sessions.remove(from);
            wa.sendText(from, "Nothing left on the receipt, so nothing was added. Send *stock* to start again.");
            return;
        }
        readBack(from, s);
    }

    private Draft line(String from, Session s, String number) {
        int index = Integer.parseInt(number) - 1;
        if (index < 0 || index >= s.drafts.size()) {
            wa.sendText(from, "There's no line " + number + " on the receipt.");
            return null;
        }
        return s.drafts.get(index);
    }

    /** Stock items whose name has every word typed (the "which one?" options first). */
    private List<Inventory> find(String merchantId, String typed, List<String> options) {
        String[] words = ShopAssistant.normalize(typed).split(" ");
        List<Inventory> all = inventoryRepository.findByMerchantId(merchantId).stream()
                .filter(i -> !Boolean.FALSE.equals(i.getIsActive())).toList();
        List<Inventory> hits = all.stream().filter(i -> {
            String name = ShopAssistant.normalize(i.getItemName());
            return Arrays.stream(words).allMatch(w -> (" " + name + " ").contains(" " + w + " ") || name.contains(w));
        }).toList();
        List<Inventory> fromOptions = hits.stream().filter(i -> options.contains(i.getItemCode())).toList();
        if (fromOptions.size() == 1) return fromOptions;
        List<Inventory> exact = hits.stream().filter(i -> ShopAssistant.normalize(i.getItemName()).equals(ShopAssistant.normalize(typed))).toList();
        return exact.size() == 1 ? exact : hits;
    }

    private void send(String from, Session s) {
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < s.drafts.size(); i++) {
            Draft d = s.drafts.get(i);
            if (d.kind == Kind.UNSURE || d.units == null) open.add(i + 1);
        }
        if (!open.isEmpty()) {
            wa.sendText(from, "Lines " + open.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                    + " still need fixing: pick the product (*3 is …* or *3 new*), give the quantity (*3 = 24*), or *remove 3*.");
            return;
        }
        List<StockReceiptService.Item> items = s.drafts.stream().map(d -> new StockReceiptService.Item(
                d.kind == Kind.NEW ? null : d.itemCode, d.name, d.units, d.unitCost, d.kind == Kind.NEW)).toList();
        StockReceipt saved;
        try {
            saved = receipts.submit(s.merchantId, from, s.supplier, s.number, s.date, s.total, s.photoPath, items);
        } catch (RuntimeException e) {
            log.warn("Stock receipt from {} could not be saved: {}", from, e.getMessage());
            wa.sendText(from, "Sorry, I couldn't send that for approval: " + e.getMessage());
            return;
        }
        sessions.remove(from);
        String approver = saved.getApproverPhone();
        boolean self = approver.equals(phone(from));
        if (!self) {
            wa.sendText(from, "✅ Sent to " + approver + " for approval. The stock is added once they approve; I'll let you know.");
        }
        boolean shown = ask(saved);
        receipts.markShown(saved.getId(), shown);
        if (!shown && !self) {
            recipients.textOne(approver, "TRIBE " + smsSafe(recipients.shopName(saved.getMerchantId())) + ": a supplier receipt from "
                    + saved.getSubmittedBy() + " is waiting for your approval before stock is added. Send APPROVALS to Tribe on WhatsApp"
                    + displayNumber() + " to see it.");
        }
    }

    // ───────────────────────────── approver side ─────────────────────────────

    /** The approver tapping Add stock / Don't add, asking for "approvals", or messaging while receipts wait. */
    public boolean handleApproval(String from, String type, String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith(APPROVE) || text.startsWith(REJECT)) {
            decide(from, text.substring(APPROVE.length()), text.startsWith(APPROVE));
            return true;
        }
        List<StockReceipt> pending = receipts.pendingFor(from);
        if (pending.isEmpty()) return false;
        String lower = ShopAssistant.normalize(text);
        if ("text".equals(type) && (WhatsAppReportApproval.LIST_WORDS.contains(lower) || WhatsAppReportApproval.APPROVE_WORDS.contains(lower))) {
            pending.forEach(r -> receipts.markShown(r.getId(), ask(r)));
            return true;
        }
        pending.stream().filter(r -> !Boolean.TRUE.equals(r.getShownOnWhatsApp()))
                .forEach(r -> receipts.markShown(r.getId(), ask(r)));
        return false;
    }

    private void decide(String from, String rawId, boolean approve) {
        long id;
        try {
            id = Long.parseLong(rawId.trim());
        } catch (NumberFormatException e) {
            return;
        }
        Optional<StockReceiptService.Applied> done;
        try {
            done = receipts.decide(id, from, approve);
        } catch (RuntimeException e) {
            log.error("Stock receipt #{} could not be applied", id, e);
            wa.sendText(from, "Sorry, the stock couldn't be added just now. Nothing was changed; please tap again in a minute.");
            return;
        }
        if (done.isEmpty()) {
            boolean handled = receipts.find(id).filter(r -> r.getStatus() != StockReceipt.Status.PENDING).isPresent();
            wa.sendText(from, handled ? "That receipt was already handled." : "That receipt isn't waiting for your approval.");
            return;
        }
        StockReceipt r = receipts.find(id).orElseThrow();
        StockReceiptService.Applied a = done.get();
        String summary = approve
                ? "✅ Stock added: " + a.units() + " units on " + a.items() + " product" + (a.items() == 1 ? "" : "s")
                + (r.getSupplierName() == null ? "" : " from " + r.getSupplierName()) + "."
                + (a.newItems().isEmpty() ? "" : "\n🆕 New products, set a selling price in Tribe before selling them: "
                + String.join(", ", a.newItems()) + ".")
                : "🚫 Not added. No stock was changed.";
        wa.sendText(from, summary);
        if (!r.getSubmittedBy().equals(r.getApproverPhone())) {
            wa.sendText(WhatsAppCloudClient.toWaNumber(r.getSubmittedBy()), (approve
                    ? r.getApproverPhone() + " approved your receipt" : r.getApproverPhone() + " did not approve your receipt")
                    + (r.getReceiptNumber() == null ? "" : " No. " + r.getReceiptNumber()) + ". " + summary);
        }
    }

    /** Sends the receipt to the approver with Add stock / Don't add. Returns whether WhatsApp accepted it. */
    boolean ask(StockReceipt r) {
        StringBuilder sb = new StringBuilder("📥 " + recipients.shopName(r.getMerchantId()) + ": add this stock? Sent by "
                + r.getSubmittedBy() + "\n");
        if (r.getSupplierName() != null) sb.append("Supplier: ").append(r.getSupplierName()).append('\n');
        if (r.getReceiptNumber() != null) sb.append("Receipt No. ").append(r.getReceiptNumber())
                .append(r.getReceiptDate() == null ? "" : " · " + r.getReceiptDate()).append('\n');
        sb.append('\n');
        List<StockReceiptService.Item> items = receipts.items(r);
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (StockReceiptService.Item i : items) {
            String line = ++n + ". " + (i.isNew() ? "🆕 " : "") + i.itemName() + " +" + i.units()
                    + (i.unitCost() == null ? "" : " @ KES " + kes(i.unitCost())) + (i.isNew() ? " (new product)" : "") + "\n";
            if (sb.length() + line.length() > 850) {
                sb.append("…and ").append(items.size() - n + 1).append(" more\n");
                break;
            }
            sb.append(line);
        }
        for (StockReceiptService.Item i : items) {
            if (i.unitCost() != null) sum = sum.add(i.unitCost().multiply(BigDecimal.valueOf(i.units())));
        }
        sb.append("\nValue KES ").append(kes(sum)).append(r.getTotal() == null ? "" : " (receipt total KES " + kes(r.getTotal()) + ")");
        List<StockReceipt> earlier = receipts.earlierWithNumber(r.getMerchantId(), r.getReceiptNumber()).stream()
                .filter(e -> !e.getId().equals(r.getId())).toList();
        if (!earlier.isEmpty()) sb.append("\n⚠️ This receipt number was already sent before. Approving adds the stock again.");
        WhatsAppCloudClient.Sent sent = wa.sendButtons(WhatsAppCloudClient.toWaNumber(r.getApproverPhone()),
                sb.length() > 1020 ? sb.substring(0, 1019) + "…" : sb.toString(), List.of(
                        new WhatsAppCloudClient.Button(APPROVE + r.getId(), "Add stock"),
                        new WhatsAppCloudClient.Button(REJECT + r.getId(), "Don't add")));
        if (sent == null || !sent.ok()) {
            log.info("Approval request for stock receipt #{} did not reach WhatsApp: {}", r.getId(), sent == null ? "no reply" : sent.error());
            return false;
        }
        return true;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String approverLabel(String merchantId) {
        String approver = receipts.approverFor(merchantId);
        return approver == null ? "the owner" : approver;
    }

    private Map<String, String> names(String merchantId) {
        Map<String, String> names = new HashMap<>();
        inventoryRepository.findByMerchantId(merchantId).forEach(i -> names.put(i.getItemCode(), i.getItemName()));
        return names;
    }

    private String displayNumber() {
        return props.getDisplayNumber() == null || props.getDisplayNumber().isBlank() ? "" : " (" + props.getDisplayNumber() + ")";
    }

    private static String phone(String from) {
        try {
            return ReportRecipientService.normalizePhone(from);
        } catch (IllegalArgumentException e) {
            return from;
        }
    }

    /** New product names in the stock list's style: upper case, single spaces. */
    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\\"]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private static String smsSafe(String text) {
        return text == null ? "" : text.replaceAll("[\\\\\"\\r\\n\\t]", " ");
    }

    /** 1,200 or 1,200.50 */
    static String kes(BigDecimal v) {
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        return String.format(Locale.ROOT, r.stripTrailingZeros().scale() <= 0 ? "%,.0f" : "%,.2f", r);
    }
}
