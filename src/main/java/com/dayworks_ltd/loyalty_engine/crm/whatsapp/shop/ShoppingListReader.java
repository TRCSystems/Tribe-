package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * Reads a shopping list typed on WhatsApp ("Nipe unga 2kg mbili, sukari, maziwa 3") against a store's
 * catalogue using Claude. Claude only says which catalogue code and how many; the caller re-checks
 * every code, price and stock level. Uncertain lines come back with alternatives so the customer can
 * choose — nothing is guessed.
 */
@Slf4j
@Component
public class ShoppingListReader {

    @Value("${anthropic.api-key:}")
    private String apiKey;

    /** Model for typed lists (short text: a small model reads these well). */
    @Value("${anthropic.model:claude-opus-5-5}")
    private String model;

    /** Model for photos of lists: handwriting needs a stronger reader. */
    @Value("${anthropic.photo-model:claude-opus-5-5}")
    private String photoModel;

    /** Reasoning effort for photos (low, medium, high): handwriting and receipts may need more than typed lists. */
    @Value("${anthropic.photo-effort:low}")
    private String photoEffort = "low";

    /**
     * A stronger model for photos the first model wasn't sure about (an unclear line, an unknown code, no quantity).
     * Clean photos stay on the cheap model; empty = never re-read.
     */
    @Value("${anthropic.photo-second-model:}")
    private String photoSecondModel = "";

    private volatile AnthropicClient client;

    public record Line(
            @JsonPropertyDescription("What the customer asked for, in their own words")
            String requested,
            @JsonPropertyDescription("Number of packs of the matched product (default 1)")
            Integer quantity,
            @JsonPropertyDescription("Catalogue code of the product, only when exactly one product clearly fits; otherwise null")
            String itemCode,
            @JsonPropertyDescription("high = one clear match; low = several products could fit; none = not in the catalogue")
            String confidence,
            @JsonPropertyDescription("Up to 5 catalogue codes that could fit when confidence is low")
            List<String> alternatives,
            @JsonPropertyDescription("Only when the customer asked for a total weight or volume rather than a number of packs (e.g. 'sugar 3kg' -> 3); otherwise null")
            Double requestedTotal,
            @JsonPropertyDescription("Unit of requestedTotal: kg, g, l or ml; otherwise null")
            String requestedUnit) {}

    public record Reading(
            @JsonPropertyDescription("One entry per product the customer asked for, in the order they asked")
            List<Line> lines,
            @JsonPropertyDescription("Name of the shop or business the order is for when it is printed or written on the list or receipt (a header, stamp or 'To:' line), exactly as written; otherwise null. Never the supplier's or distributor's own name.")
            String shopName) {
        public Reading(List<Line> lines) {
            this(lines, null);
        }
    }

    /** Liquor sizes, slang and brand short forms: shared by orders and stock counts. */
    static final String LIQUOR_NAMES = "Bottle sizes: in the catalogue 1/4 is a quarter (250 ml), 1/2 a half (375 ml), 3/4 a full bottle (750 ml). "
            + "People say \"kota\", \"kwota\", \"kuota\", \"quarter\", \"robo\" or \"250\" for 1/4; \"nusu\", \"half\" or \"375\" for 1/2; "
            + "\"mzinga\", \"full\", \"bottle kubwa\" or \"750\" for 3/4. A voice note transcript may spell these oddly "
            + "(kwota, kuota, quota, cota, nussu, mzinga, msinga). Beers and ciders are cans or 500 ml bottles. "
            + "Brand names are often misspelt or shortened: KC = Kenya Cane, GM or Meakins = General Meakings, Kaunti or Counti = County, "
            + "Captain or Captan = Captain Morgan / Captain Gold, Tripple = Triple Ace, Ken extra or Kinextra = Kane Extra, "
            + "White cup = White Cap, Dilemonte = Delmonte, Guiness = Guinness, Gilbys = Gilbeys.\n"
            + "The brand decides the product: never match a brand or abbreviation to a different brand (KC is never Kane Extra or Captain). "
            + "If that brand has no product in the size asked for, return that brand's products as low-confidence alternatives.";

    /** What kind of shop is being ordered from: decides the wording and the product hints Claude gets. */
    public enum Vertical { GROCERY, LIQUOR }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** A typed list (grocery shop, shelf prices). */
    public Reading read(String message, List<Inventory> catalogue) {
        return read(message, catalogue, Vertical.GROCERY, Map.of());
    }

    /** A typed list; {@code prices} (item code → KES) override the shelf price, e.g. a distributor's wholesale prices. */
    public Reading read(String message, List<Inventory> catalogue, Vertical vertical, Map<String, BigDecimal> prices) {
        return call(List.of(ContentBlockParam.ofText(spaceSizes(message))), systemPrompt(catalogue, vertical, prices), model, OutputConfig.Effort.LOW);
    }

    /** "Kc1/4 5" → "Kc 1/4 5": a size glued to a name confuses the reader. */
    static String spaceSizes(String text) {
        return text == null ? "" : text.replaceAll("(?<=\\p{L})(?=\\d+/\\d)", " ");
    }

    /** Whether a photo reading has anything the reader wasn't sure of, so a second look could change it. */
    static boolean unsure(Reading r, Set<String> codes) {
        if (r == null || r.lines() == null || r.lines().isEmpty()) return true;
        // A shop name of one or two letters ("x") is a misread name, worth a second look
        if (r.shopName() != null && !r.shopName().isBlank() && r.shopName().replaceAll("[^\\p{L}\\p{N}]", "").length() < 3) return true;
        for (Line l : r.lines()) {
            if (!"high".equalsIgnoreCase(l.confidence()) || l.quantity() == null) return true;
            if (l.itemCode() == null || !codes.contains(l.itemCode())) return true;
        }
        return false;
    }

    /**
     * A photo of a list (handwritten or printed), with the WhatsApp caption if the customer added one.
     * Same output as a typed list, so unclear lines are still asked about — a photo never becomes an order by itself.
     */
    public Reading readPhoto(byte[] image, String mimeType, String caption, List<Inventory> catalogue) {
        return readPhoto(image, mimeType, caption, catalogue, Vertical.GROCERY, Map.of());
    }

    public Reading readPhoto(byte[] image, String mimeType, String caption, List<Inventory> catalogue,
                             Vertical vertical, Map<String, BigDecimal> prices) {
        ContentBlockParam photo = imageBlock(image, mimeType);
        String instruction = "This is a photo of the customer's order: a handwritten list, an order book page, or a printed receipt or invoice. "
                + "Go through it line by line, top to bottom, and return one line for every product on it, including ones you can't match. "
                + "On a receipt or invoice use the quantity column, not the price or amount columns, and skip totals, tax and payment lines. "
                + "If a word is unreadable, return it as written with confidence \"none\" rather than guessing. "
                + "If a quantity is unclear, overwritten or missing but the line has a unit price and an amount, the quantity is the amount divided by the unit price (@200 and 1000 → 5). "
                + "Shop name: on a receipt or invoice the printed letterhead is the seller, never the customer; the customer is on the 'M/S', 'Sold to', 'Customer' or 'To:' line. "
                + "Return that customer name as shopName exactly as written, every word (e.g. \"MD Wines\"), or null if there is none."
                + (caption == null || caption.isBlank() ? "" : " The customer also wrote: " + caption);
        List<ContentBlockParam> content = List.of(photo, ContentBlockParam.ofText(instruction));
        String system = systemPrompt(catalogue, vertical, prices);
        Reading first = call(content, system, photoModel, effort(photoEffort));
        Set<String> codes = new HashSet<>();
        catalogue.forEach(i -> codes.add(i.getItemCode()));
        if (photoSecondModel == null || photoSecondModel.isBlank() || photoSecondModel.equals(photoModel) || !unsure(first, codes)) {
            return first;
        }
        try {
            Reading second = call(content, system, photoSecondModel, OutputConfig.Effort.LOW);
            log.info("Photo re-read with {} (first read by {} had unclear lines)", photoSecondModel, photoModel);
            return second != null && second.lines() != null && !second.lines().isEmpty() ? second : first;
        } catch (RuntimeException e) {
            log.warn("Second look at a photo with {} failed, keeping the first reading: {}", photoSecondModel, e.getMessage());
            return first;
        }
    }

    private static OutputConfig.Effort effort(String level) {
        return switch (String.valueOf(level).trim().toLowerCase(Locale.ROOT)) {
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.LOW;
        };
    }

    // ───────────────────────────── stock counts ─────────────────────────────

    public record CountLine(
            @JsonPropertyDescription("The product as written or said, in the counter's own words")
            String requested,
            @JsonPropertyDescription("Number of single units counted (bottles, cans, packs); 0 when they said there are none; null when no number was given or it can't be read")
            Integer counted,
            @JsonPropertyDescription("Code from the count list, only when exactly one product clearly fits, including the size; otherwise null")
            String itemCode,
            @JsonPropertyDescription("high = one clear product and a clear number; low = unsure of the product or the number (always low for counts from a shelf photo); none = not on the count list")
            String confidence,
            @JsonPropertyDescription("Up to 5 codes from the count list that could fit when confidence is low")
            List<String> alternatives) {}

    public record CountReading(
            @JsonPropertyDescription("One entry per product counted, in the order given")
            List<CountLine> lines,
            @JsonPropertyDescription("For photos: sheet = a written or printed list of products and numbers; shelf = a photo of the products themselves. For typed or spoken counts: text")
            String source) {}

    /** Counts typed or spoken during the nightly stock count ("Tusker 46, KC kota 12"), against the shop's count list. */
    public CountReading readCount(String text, List<Inventory> items, Vertical vertical) {
        return call(List.of(ContentBlockParam.ofText(spaceSizes(text))), countPrompt(items, vertical), model,
                OutputConfig.Effort.LOW, CountReading.class);
    }

    /**
     * A photo sent during a stock count: a count sheet (read like a list) or a shelf (counted by eye, so every
     * count comes back low-confidence and the counter must confirm it). Shelf photos go to the stronger model.
     */
    public CountReading readCountPhoto(byte[] image, String mimeType, String caption, List<Inventory> items, Vertical vertical) {
        String instruction = "This photo was sent during the shop's stock count. It is either a count sheet (a handwritten or printed list "
                + "of products with the number counted) or a photo of the products themselves on a shelf, in a store room or in crates. "
                + "Set source to \"sheet\" or \"shelf\". "
                + "For a sheet: go through it line by line and return every product with the number written next to it; if a number is "
                + "unreadable or overwritten, return counted null with confidence \"low\" rather than guessing. "
                + "For a shelf: count only units you can actually see of each product (a full crate whose bottle tops you can all see counts as "
                + "24, or 12 for 750 ml), and set confidence \"low\" on every line, because rows hidden behind the front ones can't be seen. "
                + "Never add products that are on the count list but not in the photo."
                + (caption == null || caption.isBlank() ? "" : " The counter also wrote: " + caption);
        List<ContentBlockParam> content = List.of(imageBlock(image, mimeType), ContentBlockParam.ofText(instruction));
        String system = countPrompt(items, vertical);
        CountReading first = call(content, system, photoModel, effort(photoEffort), CountReading.class);
        boolean shelf = first != null && "shelf".equalsIgnoreCase(first.source());
        if (photoSecondModel == null || photoSecondModel.isBlank() || photoSecondModel.equals(photoModel)
                || (!shelf && !unsureCount(first, items))) {
            return first;
        }
        try {
            CountReading second = call(content, system, photoSecondModel, OutputConfig.Effort.LOW, CountReading.class);
            log.info("Count photo re-read with {} ({})", photoSecondModel, shelf ? "shelf photo" : "unclear lines");
            return second != null && second.lines() != null && !second.lines().isEmpty() ? second : first;
        } catch (RuntimeException e) {
            log.warn("Second look at a count photo with {} failed, keeping the first reading: {}", photoSecondModel, e.getMessage());
            return first;
        }
    }

    static boolean unsureCount(CountReading r, List<Inventory> items) {
        if (r == null || r.lines() == null || r.lines().isEmpty()) return true;
        Set<String> codes = new HashSet<>();
        items.forEach(i -> codes.add(i.getItemCode()));
        for (CountLine l : r.lines()) {
            if (!"high".equalsIgnoreCase(l.confidence()) || l.counted() == null) return true;
            if (l.itemCode() == null || !codes.contains(l.itemCode())) return true;
        }
        return false;
    }

    static String countPrompt(List<Inventory> items, Vertical vertical) {
        StringBuilder sb = new StringBuilder("""
                You read stock counts that a Kenyan shop's staff send on WhatsApp during their nightly stock count, and match each counted product to the shop's count list below. You only report what was counted; you never know or guess how many the system expects.

                Staff write or speak in English, Swahili or Sheng, often mixed, with spelling mistakes and no punctuation. Swahili numbers: moja 1, mbili 2, tatu 3, nne 4, tano 5, sita 6, saba 7, nane 8, tisa 9, kumi 10, kumi na mbili 12, ishirini 20, ishirini na nne 24, thelathini 30, arobaini 40, hamsini 50. "Hakuna", "zero", "none", "imeisha" mean 0.

                """);
        if (vertical == Vertical.LIQUOR) {
            sb.append(LIQUOR_NAMES).append("\n\n");
        }
        sb.append("""
                For each product counted, return one line:
                - itemCode: the code from the count list, only when exactly one product clearly fits, including the size. If the brand is clear but the size isn't, leave itemCode null, set confidence "low" and list the sizes in alternatives.
                - counted: the number of single units. A crate, case or carton is 24 for 1/4 and 1/2 bottles, cans and 500 ml beers, and 12 for 3/4 (750 ml) bottles, so "crate 2 na 5" of Tusker is 53. "Dozen" is 12. If no number is given, counted is null.
                - confidence "none" when the product is not on the count list.
                Never invent codes; use only codes from the list. Ignore greetings and words that are not products.

                Count list (code | product | category | other names):
                """);
        for (Inventory i : items) {
            sb.append(i.getItemCode()).append(" | ").append(i.getItemName()).append(" | ")
                    .append(Objects.toString(i.getProductCategory(), "")).append(" | ")
                    .append(Objects.toString(i.getKeywords(), "")).append('\n');
        }
        return sb.toString();
    }

    // ───────────────────────────── supplier receipts (stock in) ─────────────────────────────

    public record ReceiptLine(
            @JsonPropertyDescription("The product exactly as printed or written on the receipt")
            String written,
            @JsonPropertyDescription("Quantity as written, with its pack word, e.g. '2 ctn', '3 cases', '24 pcs'")
            String packs,
            @JsonPropertyDescription("Number of single units received (bottles, cans, packets), converting cases/cartons/crates to units; null when the quantity can't be read")
            Integer units,
            @JsonPropertyDescription("Cost in KES of ONE single unit (line total divided by units when only the total is shown); null when no price is shown")
            Double unitCost,
            @JsonPropertyDescription("Line total in KES as printed; null when not shown")
            Double lineTotal,
            @JsonPropertyDescription("Code from the shop's product list, only when exactly one product clearly fits, including the size; otherwise null")
            String itemCode,
            @JsonPropertyDescription("high = one clear product and clear numbers; low = unsure of the product, size or numbers; none = the product is not in the shop's list at all")
            String confidence,
            @JsonPropertyDescription("Up to 5 codes from the product list that could fit when confidence is low")
            List<String> alternatives,
            @JsonPropertyDescription("When confidence is none: a clean product name in the same style as the shop's list (brand, then size such as 1/4, 1/2, 3/4, 500ML); otherwise null")
            String newItemName) {}

    public record ReceiptReading(
            @JsonPropertyDescription("False when the photo is not a supplier receipt, invoice or delivery note")
            boolean receipt,
            @JsonPropertyDescription("One entry per product line on the receipt, in order. Never include totals, VAT, discounts, deposits or empties as products.")
            List<ReceiptLine> lines,
            @JsonPropertyDescription("The seller / supplier: the letterhead or stamp at the top, exactly as printed; otherwise null")
            String supplierName,
            @JsonPropertyDescription("Receipt, invoice or delivery note number as printed; otherwise null")
            String receiptNumber,
            @JsonPropertyDescription("Date on the receipt as YYYY-MM-DD; otherwise null")
            String receiptDate,
            @JsonPropertyDescription("Grand total in KES as printed; otherwise null")
            Double total) {}

    /**
     * A photo of a supplier receipt or invoice sent to add stock. Uses the photo model, and the stronger second model
     * whenever a line is unclear: these figures change stock and cost prices.
     */
    public ReceiptReading readReceipt(byte[] image, String mimeType, String caption, List<Inventory> items, Vertical vertical) {
        String instruction = "This is a photo of a supplier receipt, invoice or delivery note for stock the shop has just received. "
                + "Read every product line with its quantity and price. If a number is unreadable or overwritten, return it as null with "
                + "confidence \"low\" rather than guessing. Check each line: quantity x unit price should equal the line total; if it doesn't, "
                + "set confidence \"low\"."
                + (caption == null || caption.isBlank() ? "" : " The sender also wrote: " + caption);
        List<ContentBlockParam> content = List.of(imageBlock(image, mimeType), ContentBlockParam.ofText(instruction));
        String system = receiptPrompt(items, vertical);
        ReceiptReading first = call(content, system, photoModel, effort(photoEffort), ReceiptReading.class);
        if (photoSecondModel == null || photoSecondModel.isBlank() || photoSecondModel.equals(photoModel)
                || !unsureReceipt(first, items)) {
            return first;
        }
        try {
            ReceiptReading second = call(content, system, photoSecondModel, OutputConfig.Effort.LOW, ReceiptReading.class);
            log.info("Receipt photo re-read with {}", photoSecondModel);
            return second != null && second.lines() != null && !second.lines().isEmpty() ? second : first;
        } catch (RuntimeException e) {
            log.warn("Second look at a receipt with {} failed, keeping the first reading: {}", photoSecondModel, e.getMessage());
            return first;
        }
    }

    static boolean unsureReceipt(ReceiptReading r, List<Inventory> items) {
        if (r == null || r.lines() == null || r.lines().isEmpty()) return true;
        Set<String> codes = new HashSet<>();
        items.forEach(i -> codes.add(i.getItemCode()));
        for (ReceiptLine l : r.lines()) {
            if ("low".equalsIgnoreCase(l.confidence()) || l.units() == null) return true;
            if (l.itemCode() != null && !codes.contains(l.itemCode())) return true;
        }
        return false;
    }

    static String receiptPrompt(List<Inventory> items, Vertical vertical) {
        StringBuilder sb = new StringBuilder("""
                You read photos of supplier receipts, invoices and delivery notes that a Kenyan shop's staff send on WhatsApp when stock arrives, and match each product line to the shop's product list below. Prices are in KES.

                """);
        if (vertical == Vertical.LIQUOR) {
            sb.append(LIQUOR_NAMES).append("\n\n");
        }
        sb.append("""
                For each product line return:
                - units: the number of single units. A case, carton (ctn), crate or box is 24 for 1/4 and 1/2 bottles, cans and 500 ml beers, and 12 for 3/4 (750 ml) bottles, unless the receipt states a different pack size (e.g. "6x750ml" or "ctn of 12"). "Dozen" is 12, "pcs" or "btl" are single units.
                - unitCost: KES for ONE single unit, so a carton price is divided by the units in the carton.
                - itemCode: the code from the list, only when exactly one product clearly fits, including the size. If the brand is clear but the size isn't, leave itemCode null, set confidence "low" and list the sizes in alternatives.
                - confidence "none" only when the product is clearly not in the list at all; then give newItemName.
                The supplier is the business at the top (letterhead, stamp); a "M/S" or "To:" line is the customer (this shop), not the supplier. Never invent codes; use only codes from the list.

                Product list (code | product | category | other names):
                """);
        for (Inventory i : items) {
            sb.append(i.getItemCode()).append(" | ").append(i.getItemName()).append(" | ")
                    .append(Objects.toString(i.getProductCategory(), "")).append(" | ")
                    .append(Objects.toString(i.getKeywords(), "")).append('\n');
        }
        return sb.toString();
    }

    /**
     * Any short text read into a structured answer with the typed-list model (e.g. social media comments).
     * The system prompt is cached, so keep the per-shop part of it stable.
     */
    public <T> T readText(String system, String text, Class<T> format) {
        return readText(system, text, format, null);
    }

    /** The same with another model (e.g. a stronger one for a second look); blank = the typed-list model. */
    public <T> T readText(String system, String text, Class<T> format, String modelId) {
        return call(List.of(ContentBlockParam.ofText(text)), system, modelId == null || modelId.isBlank() ? model : modelId,
                OutputConfig.Effort.LOW, format);
    }

    private static ContentBlockParam imageBlock(byte[] image, String mimeType) {
        Base64ImageSource.MediaType type = switch (String.valueOf(mimeType).toLowerCase(Locale.ROOT)) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
        return ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(image)).mediaType(type).build())
                .build());
    }

    private Reading call(List<ContentBlockParam> content, String system, String modelId, OutputConfig.Effort effort) {
        return call(content, system, modelId, effort, Reading.class);
    }

    private <T> T call(List<ContentBlockParam> content, String system, String modelId, OutputConfig.Effort effort, Class<T> format) {
        if (!isConfigured()) throw new IllegalStateException("anthropic.api-key is not set");
        try {
            return first(client().messages().create(params(content, system, modelId, effort, true, format)));
        } catch (BadRequestException e) {
            // Only the optional refusal fallback can be retried; anything else (e.g. no credit) is a real failure
            if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("fallback")) throw e;
            log.warn("Shopping list read retried without fallbacks: {}", e.getMessage());
            return first(client().messages().create(params(content, system, modelId, effort, false, format)));
        }
    }

    private <T> StructuredMessageCreateParams<T> params(List<ContentBlockParam> content, String system, String modelId,
                                                        OutputConfig.Effort effort, boolean fallbacks, Class<T> format) {
        StructuredMessageCreateParams.Builder<T> b = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(4000L)
                // Extraction against a short catalogue: low effort keeps WhatsApp replies quick
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(effort)
                        .format(format)
                        .build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessageOfBlockParams(content);
        if (fallbacks) {
            // If the main model declines, the API re-runs the request on a fallback model in the same call
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b.build();
    }

    private static <T> T first(StructuredMessage<T> response) {
        if (response.stopReason().map(r -> r.toString().equalsIgnoreCase("refusal")).orElse(false)) {
            throw new IllegalStateException("The list could not be read");
        }
        return response.content().stream()
                .flatMap(c -> c.text().stream())
                .map(t -> t.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No reading returned"));
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(45)).build();
                }
                c = client;
            }
        }
        return c;
    }

    static String systemPrompt(List<Inventory> catalogue) {
        return systemPrompt(catalogue, Vertical.GROCERY, Map.of());
    }

    static String systemPrompt(List<Inventory> catalogue, Vertical vertical, Map<String, BigDecimal> prices) {
        StringBuilder sb = new StringBuilder();
        if (vertical == Vertical.LIQUOR) {
            sb.append("""
                You read stock orders that bar, wines & spirits and liquor shop owners send on WhatsApp to their liquor distributor in Kenya, and match each requested product to the distributor's catalogue below.

                Shop owners write in English, Swahili or Sheng, often mixed, with spelling mistakes and no punctuation. Swahili numbers: moja 1, mbili 2, tatu 3, nne 4, tano 5, sita 6, saba 7, nane 8, tisa 9, kumi 10, kumi na mbili 12, ishirini 20, ishirini na nne 24. "Nipe", "naomba", "nataka", "leta" mean "give me / bring me".

                """).append(LIQUOR_NAMES).append("""

                Some catalogue names have no size in them. When a receipt shows a unit price, the catalogue product of that brand with the closest price is the right one (a KC 1/4 written at @268 is the Kenya Cane product priced 268).

                For each product the shop asks for, return one line:
                - itemCode: the catalogue code, only when exactly one product clearly fits, including the size. If the brand is clear but the size isn't (for example "Kibao 6" when there are Kibao 1/4, 1/2 and 3/4), leave itemCode null, set confidence to "low" and list the sizes in alternatives, most common first.
                - confidence "none" when nothing in the catalogue fits (alternatives empty).
                - quantity: number of single bottles or cans. A crate, case, carton or box is 24 for 1/4 bottles, 1/2 bottles, cans and 500 ml beers, and 12 for 3/4 (750 ml) bottles, so "crate 2 Tusker" is 48 and "case ya Kibao mzinga" is 12. "Dozen" is 12. If no quantity is given, use 1.
                - requestedTotal and requestedUnit: leave null (liquor is ordered by the bottle or by the case, not by volume).
                Never invent codes; use only codes from the catalogue. Ignore greetings and words that are not products.

                Catalogue (code | product | category | KES price | other names):
                """);
        } else {
            sb.append("""
                You read shopping lists that customers send on WhatsApp to a Kenyan supermarket, and match each requested item to the store's catalogue below.

                Customers write in English, Swahili or Sheng, often mixed, with spelling mistakes and no punctuation. Swahili numbers: moja 1, mbili 2, tatu 3, nne 4, tano 5, sita 6, saba 7, nane 8, tisa 9, kumi 10, ishirini 20. "Nipe", "naomba", "nataka" mean "give me / I want". "Pakiti" is a packet, "chupa" a bottle, "tray" of eggs is one unit.

                For each product the customer asks for, return one line:
                - itemCode: the catalogue code, only when exactly one product clearly fits. If the customer gives no brand or size and several products fit (for example "maziwa" when there are several milk sizes and brands), leave itemCode null, set confidence to "low" and list the likely codes in alternatives, most likely first.
                - confidence "none" when nothing in the catalogue fits (alternatives empty).
                - quantity: number of packs of that catalogue product. "Unga 2kg mbili" means 2 packs of a 2kg product, not 4. If no quantity is given, use 1.
                - Totals: when the customer asks for a total weight or volume rather than a number of packs (for example "sugar 3kg", "mafuta lita 5", "maziwa 2 litres"), set requestedTotal and requestedUnit (kg, g, l or ml), and set quantity to the packs of your chosen product that make that total (sugar 3kg with a 1kg pack = 3). When the amount is just the pack size and a count is also given ("unga 2kg mbili"), it is not a total: leave requestedTotal null.
                Never invent codes; use only codes from the catalogue. Ignore greetings and words that are not products.

                Catalogue (code | product | category | KES price | other names):
                """);
        }
        for (Inventory i : catalogue) {
            BigDecimal price = prices == null ? null : prices.get(i.getItemCode());
            if (price == null) price = i.getUnitPrice();
            sb.append(i.getItemCode()).append(" | ").append(i.getItemName()).append(" | ")
                    .append(Objects.toString(i.getProductCategory(), "")).append(" | ")
                    .append(price == null ? "" : price.stripTrailingZeros().toPlainString()).append(" | ")
                    .append(Objects.toString(i.getKeywords(), "")).append('\n');
        }
        return sb.toString();
    }
}
