package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.inventory.enums.CountTrigger;
import com.dayworks_ltd.loyalty_engine.inventory.enums.StockCountStatus;
import com.dayworks_ltd.loyalty_engine.inventory.models.CloseDay;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.models.StockCountLine;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.ReportRecipientService;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockCountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shop's nightly stock count on WhatsApp, for the shop's staff numbers (its registered phone and the
 * report recipients the owner added). Staff send a photo of their count sheet, a shelf photo, a voice note
 * or typed counts; Claude reads them against tonight's count list; the counter sees what was read and taps
 * Save. Saving goes through {@link StockCountService#submitCount}, so the count stays blind (the expected
 * figure is never shown), a first mismatch asks for a recount, and shortages alert the owner as before.
 *
 * Rules: nothing is saved until the counter confirms; counts read from a shelf photo are always marked
 * "check", because rows hidden behind the front ones can't be seen; numbers that aren't staff never reach
 * the count.
 */
@Slf4j
@Service
public class WhatsAppStockCount {

    /** Count mode ends by itself after this much silence; the next message goes back to ordering. */
    static final long IDLE_MINUTES = 180;
    static final Set<String> START_WORDS = Set.of("count", "stock count", "stockcount", "start count", "count stock",
            "stock take", "stocktake", "hesabu", "hesabu stock", "hesabu ya stock");
    private static final Set<String> STOP_WORDS = Set.of("stop", "exit", "quit", "stop count", "back", "cancel",
            "order", "orders", "menu", "toka");
    private static final Set<String> DONE_WORDS = Set.of("done", "finish", "finished", "close", "close count",
            "finish count", "nimemaliza", "tumemaliza");
    private static final Set<String> LIST_WORDS = Set.of("list", "items", "left", "remaining", "whats left",
            "what is left", "show list", "baki", "zilizobaki");
    private static final Set<String> SAVE_WORDS = Set.of("ok", "okay", "save", "yes", "ndio", "sawa", "correct", "confirm");
    private static final Set<String> DISCARD_WORDS = Set.of("discard", "no", "hapana", "wrong", "clear");
    private static final Set<StockCountStatus> UNCOUNTED =
            EnumSet.of(StockCountStatus.PENDING, StockCountStatus.RECOUNT_REQUESTED);
    /** "3 14", "3 = 14" (normalized), "3 is 14", "3 ni 14": changes line 3 of the counts read back to 14. */
    private static final Pattern FIX = Pattern.compile("^(\\d{1,2}) (?:is |ni |to )?(\\d{1,5})$");
    private static final int MAX_COUNT = 100_000;
    private static final int TEXT_LIMIT = 3500;

    private final WhatsAppCloudClient wa;
    private final StockCountService counts;
    private final ReportRecipientService recipients;
    private final ShoppingListReader reader;
    private final VoiceTranscriber transcriber;
    private final InventoryRepository inventoryRepository;
    private final MediaArchive mediaArchive;

    public WhatsAppStockCount(WhatsAppCloudClient wa, StockCountService counts, ReportRecipientService recipients,
                              ShoppingListReader reader, VoiceTranscriber transcriber,
                              InventoryRepository inventoryRepository, MediaArchive mediaArchive) {
        this.wa = wa;
        this.counts = counts;
        this.recipients = recipients;
        this.reader = reader;
        this.transcriber = transcriber;
        this.inventoryRepository = inventoryRepository;
        this.mediaArchive = mediaArchive;
    }

    /** One count read back to the counter, not saved yet. check = the reader wasn't sure (or it came from a shelf). */
    record Draft(Long lineId, String itemName, int counted, boolean check) {}

    /** A number in count mode. */
    static final class Session {
        final String merchantId;
        final List<Draft> drafts = new ArrayList<>();
        volatile LocalDateTime touched = LocalDateTime.now();

        Session(String merchantId) {
            this.merchantId = merchantId;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // ───────────────────────────── entry ─────────────────────────────

    /**
     * Evening job: prepares tonight's count and sends the counter the items that sold or were restocked today,
     * with a Start count button. Falls back to SMS when WhatsApp can't reach them (no chat in 24 hours).
     * Returns false when there was nothing to send.
     */
    public boolean sendCountList(String merchantId, String phone) {
        CloseDay day;
        try {
            day = counts.startCount(merchantId, "SYSTEM");
        } catch (IllegalStateException e) {
            log.info("Count list for merchant {} not sent: {}", merchantId, e.getMessage());
            return false;
        }
        List<StockCountLine> left = uncounted(day);
        if (left.isEmpty()) return false;
        List<String> moved = left.stream().filter(l -> l.getTrigger() == CountTrigger.MOVEMENT_BASED)
                .map(StockCountLine::getItemName).toList();
        String shop = recipients.shopName(merchantId);

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < moved.size(); i++) list.append(i + 1).append(". ").append(moved.get(i)).append('\n');
        String intro = "📦 Tonight's stock count, " + shop + "\n" + (moved.isEmpty()
                ? "Nothing sold or restocked today. " + left.size() + " items are on the list.\n"
                : "Please count these " + moved.size() + " item" + (moved.size() == 1 ? "" : "s") + " that sold or were restocked today:\n");
        String ask = "\nTap *Start count*, then send a photo of your count sheet, a voice note, or type them: Tusker 500ml 46.";
        String to = WhatsAppCloudClient.toWaNumber(phone);

        WhatsAppCloudClient.Sent sent;
        if (intro.length() + list.length() + ask.length() <= 1000) {
            sent = wa.sendButtons(to, intro + list + ask, List.of(new WhatsAppCloudClient.Button("SC:START", "Start count")));
        } else {
            sent = wa.sendText(to, intro + list);
            if (sent != null && sent.ok()) {
                wa.sendButtons(to, ask.trim(), List.of(new WhatsAppCloudClient.Button("SC:START", "Start count")));
            }
        }
        if (sent != null && sent.ok()) return true;

        log.info("Count list for merchant {} did not reach WhatsApp {}, sending SMS", merchantId, phone);
        StringBuilder sms = new StringBuilder("TRIBE " + shop + ": tonight's stock count. ");
        if (moved.isEmpty()) {
            sms.append(left.size()).append(" items to count.");
        } else {
            sms.append("Count these ").append(moved.size()).append(" items sold or restocked today: ");
            int shown = 0;
            for (String name : moved) {
                if (sms.length() + name.length() > 520) break;
                sms.append(shown == 0 ? "" : "; ").append(name);
                shown++;
            }
            if (shown < moved.size()) sms.append(" +").append(moved.size() - shown).append(" more");
            sms.append(".");
        }
        sms.append(" Then send COUNT to Tribe on WhatsApp.");
        recipients.textOne(phone, sms.toString().replaceAll("[\\\\\"\\r\\n\\t]", " "));
        return true;
    }

    /** Whether this number may count for the shop (used to mention the count in the welcome message). */
    public boolean isStaff(String from, String merchantId) {
        return recipients.isStaffNumber(merchantId, from);
    }

    /**
     * Takes the message when the number is in count mode or asks to start a count; returns false to leave it
     * to ordering. Tapping an ordering button (or "stop") leaves count mode.
     */
    public boolean handle(String from, String merchantId, ShoppingListReader.Vertical vertical, String type,
                          String content, Map<String, Object> message) {
        String text = content == null ? "" : content.trim();
        String lower = ShopAssistant.normalize(text);
        boolean tapped = "interactive".equals(type) || "button".equals(type);
        Session s = active(from, merchantId);
        // A count button tapped after count mode timed out picks the count up again instead of reaching ordering
        boolean start = "SC:START".equals(text) || (s == null && tapped && text.startsWith("SC:"))
                || ("text".equals(type) && START_WORDS.contains(lower));
        if (s == null && !start) return false;
        if (s != null && tapped && !text.startsWith("SC:")) {
            sessions.remove(from);
            return false;
        }
        if (!recipients.isStaffNumber(merchantId, from)) {
            sessions.remove(from);
            wa.sendText(from, "Stock count on WhatsApp is only for the shop's staff numbers. Ask the owner to add your number "
                    + "in Tribe under Stock count → Report recipients.");
            return true;
        }
        if (start) {
            begin(from, merchantId, s != null);
            return true;
        }
        s.touched = LocalDateTime.now();
        switch (String.valueOf(type)) {
            case "image" -> photo(from, s, vertical, message);
            case "audio" -> voice(from, s, vertical, message);
            case "text", "interactive", "button" -> words(from, s, vertical, text, false);
            default -> wa.sendText(from, "During the stock count, send a photo of your count sheet, a voice note, or type the counts. "
                    + "Send *stop* to go back to ordering.");
        }
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

    // ───────────────────────────── steps ─────────────────────────────

    private void begin(String from, String merchantId, boolean resumed) {
        CloseDay day;
        try {
            day = counts.startCount(merchantId, counter(from));
        } catch (IllegalStateException e) {
            sessions.remove(from);
            wa.sendText(from, "Tonight's stock count is already closed. The owner has the report.");
            return;
        }
        Session s = sessions.computeIfAbsent(from, k -> new Session(merchantId));
        s.touched = LocalDateTime.now();
        List<StockCountLine> left = uncounted(day);
        String shop = recipients.shopName(merchantId);
        if (left.isEmpty()) {
            wa.sendButtons(from, "📦 Stock count, " + shop + ": everything on tonight's list is counted. Close the count?",
                    List.of(new WhatsAppCloudClient.Button("SC:FINISH", "Close count"),
                            new WhatsAppCloudClient.Button("SC:STOP", "Not now")));
            return;
        }
        List<String> recount = left.stream().filter(l -> l.getStatus() == StockCountStatus.RECOUNT_REQUESTED)
                .map(StockCountLine::getItemName).toList();
        long moved = left.stream().filter(l -> l.getTrigger() == CountTrigger.MOVEMENT_BASED).count();
        String body = "📦 Stock count, " + shop + (resumed ? " (continuing)" : "") + "\n"
                + left.size() + (left.size() == 1 ? " item" : " items") + " to count"
                + (moved > 0 ? ", " + moved + " sold or restocked today" : "") + ".\n"
                + (recount.isEmpty() ? "" : "🔁 Count again first: " + String.join(", ", recount) + "\n")
                + "\nSend your counts any way you like:\n"
                + "• a photo of your count sheet 📷\n"
                + (transcriber.isConfigured() ? "• a voice note 🎤\n" : "")
                + "• or type them: Tusker 500ml 46, KC kota 12\n\n"
                + "I'll read them back before anything is saved. *done* finishes the count, *stop* goes back to ordering.";
        wa.sendButtons(from, truncate(body, 1000), List.of(
                new WhatsAppCloudClient.Button("SC:LIST", "Items to count"),
                new WhatsAppCloudClient.Button("SC:STOP", "Back to ordering")));
    }

    private void words(String from, Session s, ShoppingListReader.Vertical vertical, String text, boolean spoken) {
        String lower = ShopAssistant.normalize(text);
        if ("SC:SAVE".equals(text) || (!s.drafts.isEmpty() && SAVE_WORDS.contains(lower))) {
            save(from, s);
            return;
        }
        if ("SC:DISCARD".equals(text) || (!s.drafts.isEmpty() && (DISCARD_WORDS.contains(lower) || "cancel".equals(lower)))) {
            s.drafts.clear();
            wa.sendText(from, "Discarded, nothing was saved. Send the counts again when ready.");
            return;
        }
        if ("SC:STOP".equals(text) || STOP_WORDS.contains(lower)) {
            sessions.remove(from);
            wa.sendText(from, "Left the stock count. Counts you saved are kept: send *count* to carry on.");
            return;
        }
        if ("SC:LIST".equals(text) || LIST_WORDS.contains(lower)) {
            list(from, s);
            return;
        }
        if ("SC:FINISH".equals(text)) {
            finish(from, s);
            return;
        }
        if ("SC:CONTINUE".equals(text)) {
            wa.sendText(from, "Okay, keep counting. Send *done* when you're finished.");
            return;
        }
        if ("SC:DONE".equals(text) || DONE_WORDS.contains(lower)) {
            askFinish(from, s);
            return;
        }
        Matcher fix = FIX.matcher(lower);
        if (!s.drafts.isEmpty() && fix.matches()) {
            int index = Integer.parseInt(fix.group(1)) - 1;
            int value = Integer.parseInt(fix.group(2));
            if (index < 0 || index >= s.drafts.size() || value > MAX_COUNT) {
                wa.sendText(from, "There's no line " + (index + 1) + ". Fix a line like \"2 = 14\" (line 2 is now 14).");
                return;
            }
            Draft d = s.drafts.get(index);
            s.drafts.set(index, new Draft(d.lineId(), d.itemName(), value, false));
            readBack(from, s, "Changed " + d.itemName() + " to " + value + ".", false, List.of());
            return;
        }
        if (!lower.matches(".*\\p{L}.*")) {
            wa.sendText(from, "Send counts with the product name, like \"Tusker 500ml 46\", or a photo of your count sheet.");
            return;
        }
        if (!reader.isConfigured()) {
            wa.sendText(from, "Reading counts isn't switched on yet. Please use Stock count in Tribe.");
            return;
        }
        List<StockCountLine> lines = lines(from, s);
        if (lines == null) return;
        ShoppingListReader.CountReading reading;
        try {
            reading = reader.readCount(text, countItems(s, lines), vertical);
        } catch (Exception e) {
            log.warn("Stock count from {} could not be read: {}", from, e.getMessage());
            wa.sendText(from, "Sorry, I couldn't read that just now. Please send it again.");
            return;
        }
        apply(from, s, lines, reading, spoken ? "voice" : "text");
    }

    @SuppressWarnings("unchecked")
    private void photo(String from, Session s, ShoppingListReader.Vertical vertical, Map<String, Object> message) {
        if (!reader.isConfigured()) {
            wa.sendText(from, "Reading photos isn't switched on yet. Please type the counts.");
            return;
        }
        Map<String, Object> image = message != null && message.get("image") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        WhatsAppCloudClient.Media media = wa.downloadMedia(image.get("id") == null ? null : image.get("id").toString());
        if (media == null) {
            wa.sendText(from, "Sorry, I couldn't open that photo. Please send it again.");
            return;
        }
        mediaArchive.save(media.bytes(), media.mimeType(), "count", from);
        List<StockCountLine> lines = lines(from, s);
        if (lines == null) return;
        wa.sendText(from, "📷 Got it, reading your count…");
        ShoppingListReader.CountReading reading;
        try {
            String caption = image.get("caption") == null ? null : image.get("caption").toString();
            reading = reader.readCountPhoto(media.bytes(), media.mimeType(), caption, countItems(s, lines), vertical);
        } catch (Exception e) {
            log.warn("Stock count photo from {} could not be read: {}", from, e.getMessage());
            wa.sendText(from, "Sorry, I couldn't read that photo. Try a clearer photo in good light, or type the counts.");
            return;
        }
        apply(from, s, lines, reading, reading != null && "shelf".equalsIgnoreCase(reading.source()) ? "shelf" : "sheet");
    }

    @SuppressWarnings("unchecked")
    private void voice(String from, Session s, ShoppingListReader.Vertical vertical, Map<String, Object> message) {
        if (!transcriber.isConfigured()) {
            wa.sendText(from, "Voice notes aren't switched on yet. Please type the counts or send a photo of your count sheet.");
            return;
        }
        Map<String, Object> audio = message != null && message.get("audio") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        WhatsAppCloudClient.Media media = wa.downloadMedia(audio.get("id") == null ? null : audio.get("id").toString());
        if (media == null) {
            wa.sendText(from, "Sorry, I couldn't open that voice note. Please send it again.");
            return;
        }
        mediaArchive.save(media.bytes(), media.mimeType(), "count-voice", from);
        List<StockCountLine> lines = lines(from, s);
        if (lines == null) return;
        String heard;
        try {
            heard = transcriber.transcribe(media.bytes(), media.mimeType(), countItems(s, lines),
                    vertical == ShoppingListReader.Vertical.LIQUOR ? "Sizes: kota, kwota, robo, nusu, half, mzinga, crate" : null);
        } catch (Exception e) {
            log.warn("Stock count voice note from {} could not be transcribed: {}", from, e.getMessage());
            heard = null;
        }
        if (heard == null || heard.isBlank() || !heard.matches("(?s).*\\p{L}.*")) {
            wa.sendText(from, "Sorry, I couldn't hear that clearly. Try again somewhere quieter, or type the counts.");
            return;
        }
        wa.sendText(from, "🎤 I heard: \"" + truncate(heard, 500) + "\"");
        words(from, s, vertical, heard, true);
    }

    /** Turns what was read into draft counts (one per count line; a later reading of the same item replaces it). */
    private void apply(String from, Session s, List<StockCountLine> lines, ShoppingListReader.CountReading reading, String source) {
        if (reading == null || reading.lines() == null || reading.lines().isEmpty()) {
            wa.sendText(from, "I couldn't find any counts in that. Send them like \"Tusker 500ml 46\", or a clear photo of your count sheet.");
            return;
        }
        Map<String, StockCountLine> byCode = new HashMap<>();
        lines.forEach(l -> byCode.put(l.getItemCode(), l));
        boolean shelf = "shelf".equals(source);
        List<String> notes = new ArrayList<>();
        int read = 0;
        for (ShoppingListReader.CountLine c : reading.lines()) {
            String asked = c.requested() == null || c.requested().isBlank() ? "an item" : "\"" + c.requested().trim() + "\"";
            StockCountLine line = c.itemCode() == null ? null : byCode.get(c.itemCode());
            if (line == null) {
                List<String> options = c.alternatives() == null ? List.of() : c.alternatives().stream()
                        .map(byCode::get).filter(Objects::nonNull).map(StockCountLine::getItemName).limit(4).toList();
                notes.add(options.isEmpty()
                        ? "❓ " + asked + " isn't on tonight's count list."
                        : "❓ " + asked + ": which one? " + String.join(", ", options) + ". Send it again with the size.");
                continue;
            }
            if (c.counted() == null || c.counted() < 0 || c.counted() > MAX_COUNT) {
                notes.add("❓ " + line.getItemName() + ": how many? Send it again with the number.");
                continue;
            }
            if (!UNCOUNTED.contains(line.getStatus())) {
                notes.add("ℹ️ " + line.getItemName() + " is already counted tonight.");
                continue;
            }
            s.drafts.removeIf(d -> d.lineId().equals(line.getId()));
            s.drafts.add(new Draft(line.getId(), line.getItemName(), c.counted(),
                    shelf || !"high".equalsIgnoreCase(c.confidence())));
            read++;
        }
        log.info("Stock count from {} ({}): {} counts read, {} notes", from, source, read, notes.size());
        String head = read == 0 ? null
                : shelf ? "📷 From your shelf photo I could see the counts below. These are only what's visible, so please check every number."
                : "Here's what I read" + ("voice".equals(source) ? " from your voice note" : "sheet".equals(source) ? " from your count sheet" : "") + ".";
        readBack(from, s, head, shelf, notes);
    }

    /** Sends the counts waiting to be saved (numbered, so "2 = 14" can fix one) and asks to save them. */
    private void readBack(String from, Session s, String head, boolean shelf, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        if (head != null) sb.append(head).append("\n\n");
        for (int i = 0; i < s.drafts.size(); i++) {
            Draft d = s.drafts.get(i);
            sb.append(i + 1).append(". ").append(d.itemName()).append(": *").append(d.counted()).append('*')
                    .append(d.check() ? " ⚠️ check" : "").append('\n');
        }
        if (!notes.isEmpty()) sb.append('\n').append(String.join("\n", notes)).append('\n');
        sendLong(from, sb.toString().trim());
        if (s.drafts.isEmpty()) return;
        wa.sendButtons(from, (shelf ? "Count anything hidden behind the front row too. " : "")
                        + "Save these " + s.drafts.size() + (s.drafts.size() == 1 ? " count" : " counts")
                        + "? Fix a line by typing its number and the right count, like \"2 = 14\".",
                List.of(new WhatsAppCloudClient.Button("SC:SAVE", "Save counts"),
                        new WhatsAppCloudClient.Button("SC:DISCARD", "Discard")));
    }

    private void save(String from, Session s) {
        if (s.drafts.isEmpty()) {
            wa.sendText(from, "There's nothing waiting to be saved. Send counts first.");
            return;
        }
        int saved = 0;
        List<String> recount = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Draft d : List.copyOf(s.drafts)) {
            try {
                StockCountLine line = counts.submitCount(s.merchantId, counter(from), d.lineId(), d.counted());
                if (line.getStatus() == StockCountStatus.RECOUNT_REQUESTED) recount.add(d.itemName());
                else saved++;
            } catch (IllegalStateException | IllegalArgumentException e) {
                failed.add(d.itemName() + ": " + e.getMessage());
            }
        }
        s.drafts.clear();
        List<StockCountLine> lines = lines(from, s);
        if (lines == null) return;
        long left = lines.stream().filter(l -> UNCOUNTED.contains(l.getStatus())).count();
        StringBuilder sb = new StringBuilder();
        if (saved > 0) sb.append("✅ Saved ").append(saved).append(saved == 1 ? " count." : " counts.").append('\n');
        // Blind count: say which items to count again, never which way they're off
        if (!recount.isEmpty()) sb.append("🔁 Please count these again and send the new number: ")
                .append(String.join(", ", recount)).append('\n');
        if (!failed.isEmpty()) sb.append("Not saved: ").append(String.join("; ", failed)).append('\n');
        if (left == 0) {
            sendLong(from, sb.toString().trim());
            wa.sendButtons(from, "Everything on tonight's list is counted 🎉 Close the count and send the owner the report?",
                    List.of(new WhatsAppCloudClient.Button("SC:FINISH", "Close count"),
                            new WhatsAppCloudClient.Button("SC:CONTINUE", "Not yet")));
            return;
        }
        sb.append('\n').append(left).append(left == 1 ? " item" : " items")
                .append(" still to count. Send more counts, *list* to see them, or *done* to finish.");
        sendLong(from, sb.toString().trim());
    }

    private void list(String from, Session s) {
        List<StockCountLine> lines = lines(from, s);
        if (lines == null) return;
        List<StockCountLine> left = lines.stream().filter(l -> UNCOUNTED.contains(l.getStatus()))
                .sorted(Comparator.comparing((StockCountLine l) -> l.getStatus() != StockCountStatus.RECOUNT_REQUESTED)
                        .thenComparing(l -> l.getTrigger() != CountTrigger.MOVEMENT_BASED))
                .toList();
        if (left.isEmpty()) {
            wa.sendButtons(from, "Everything on tonight's list is counted. Close the count?",
                    List.of(new WhatsAppCloudClient.Button("SC:FINISH", "Close count"),
                            new WhatsAppCloudClient.Button("SC:CONTINUE", "Not yet")));
            return;
        }
        StringBuilder sb = new StringBuilder("📋 Still to count (" + left.size() + "):\n");
        for (StockCountLine l : left) {
            sb.append("• ").append(l.getItemName())
                    .append(l.getStatus() == StockCountStatus.RECOUNT_REQUESTED ? " 🔁 count again"
                            : l.getTrigger() == CountTrigger.MOVEMENT_BASED ? " (moved today)" : "")
                    .append('\n');
        }
        sendLong(from, sb.toString().trim());
    }

    private void askFinish(String from, Session s) {
        if (!s.drafts.isEmpty()) {
            wa.sendButtons(from, "You have " + s.drafts.size() + " counts that aren't saved yet. Save them first?",
                    List.of(new WhatsAppCloudClient.Button("SC:SAVE", "Save counts"),
                            new WhatsAppCloudClient.Button("SC:DISCARD", "Discard")));
            return;
        }
        List<StockCountLine> lines = lines(from, s);
        if (lines == null) return;
        long left = lines.stream().filter(l -> UNCOUNTED.contains(l.getStatus())).count();
        wa.sendButtons(from, "Close tonight's stock count?"
                        + (left > 0 ? " " + left + (left == 1 ? " item hasn't" : " items haven't") + " been counted and will be marked NOT COUNTED." : "")
                        + " The owner gets the report by SMS.",
                List.of(new WhatsAppCloudClient.Button("SC:FINISH", "Close count"),
                        new WhatsAppCloudClient.Button("SC:CONTINUE", "Keep counting")));
    }

    private void finish(String from, Session s) {
        if (!s.drafts.isEmpty()) {
            askFinish(from, s);
            return;
        }
        try {
            CloseDay day = counts.finalizeCount(s.merchantId, counter(from), "Closed on WhatsApp");
            sessions.remove(from);
            wa.sendText(from, "✅ Tonight's stock count is closed: " + nz(day.getTotalSkusCounted()) + " of "
                    + nz(day.getTotalSkusQueued()) + " items counted. The owner gets the report. Asante!");
        } catch (IllegalStateException e) {
            sessions.remove(from);
            wa.sendText(from, "Tonight's stock count is already closed. The owner has the report.");
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Tonight's count lines, or null (with a message, and count mode ended) when the count is closed or gone. */
    private List<StockCountLine> lines(String from, Session s) {
        CloseDay day = counts.findSession(s.merchantId, counts.today()).orElse(null);
        if (day == null || day.isClosed()) {
            sessions.remove(from);
            wa.sendText(from, "Tonight's stock count is closed. Send *count* tomorrow evening to start the next one.");
            return null;
        }
        return counts.getLines(day);
    }

    private List<StockCountLine> uncounted(CloseDay day) {
        return counts.getLines(day).stream().filter(l -> UNCOUNTED.contains(l.getStatus())).toList();
    }

    /** Tonight's count list in the reader's catalogue shape, with each product's other names and category. */
    private List<Inventory> countItems(Session s, List<StockCountLine> lines) {
        Map<String, Inventory> stock = new HashMap<>();
        inventoryRepository.findByMerchantId(s.merchantId).forEach(i -> stock.put(i.getItemCode(), i));
        List<Inventory> items = new ArrayList<>();
        for (StockCountLine l : lines) {
            Inventory known = stock.get(l.getItemCode());
            Inventory i = new Inventory();
            i.setItemCode(l.getItemCode());
            i.setItemName(l.getItemName());
            if (known != null) {
                i.setProductCategory(known.getProductCategory());
                i.setKeywords(known.getKeywords());
            }
            items.add(i);
        }
        return items;
    }

    /** Who counted, as shown on the count report: "WhatsApp 0712345678". */
    private static String counter(String from) {
        try {
            return "WhatsApp " + ReportRecipientService.normalizePhone(from);
        } catch (IllegalArgumentException e) {
            return "WhatsApp " + from;
        }
    }

    private void sendLong(String to, String text) {
        if (text.isBlank()) return;
        String rest = text;
        while (rest.length() > TEXT_LIMIT) {
            int cut = rest.lastIndexOf('\n', TEXT_LIMIT);
            if (cut <= 0) cut = TEXT_LIMIT;
            wa.sendText(to, rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isBlank()) wa.sendText(to, rest);
    }

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
