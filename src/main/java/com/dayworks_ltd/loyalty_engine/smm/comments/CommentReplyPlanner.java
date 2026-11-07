package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Decides how to answer one comment: the public reply, the private message (DM), whether to hide the comment and
 * whether a person should take over. Prices, colours, sizes and stock come only from the catalogue items passed in;
 * the AI reading supplies what was asked, never the answer.
 */
public final class CommentReplyPlanner {

    /** Meta allows one private reply per comment, within 7 days of the comment. */
    static final Duration PRIVATE_REPLY_WINDOW = Duration.ofDays(7);

    /** Kenyan mobile numbers written publicly: 0712 345 678, 0112345678, +254 712345678, 254712345678. */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?254[\\s-]?|0)[17]\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{3}(?!\\d)");

    /** Intents that make the commenter a buyer whatever the buyer flag says. */
    static final Set<Intent> BUYING = EnumSet.of(Intent.PRICE, Intent.LOCATION, Intent.AVAILABILITY,
            Intent.VARIANT, Intent.READY_TO_BUY, Intent.BOOKING, Intent.CONTACT);
    static final Set<String> BUYING_NAMES = BUYING.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * One sellable item: a product in one colour and size with its live stock, or a service (stock null: a salon
     * treatment, nail set, etc. that is booked rather than taken off a shelf).
     */
    public record Item(String productCode, String name, String colour, String size, BigDecimal price, Integer stock) {
        boolean available() {
            return stock == null || stock > 0;
        }

        boolean service() {
            return stock == null;
        }
    }

    /** The business's answers that don't depend on the product. orderLink orders goods or books services. */
    public record Shop(String name, String location, String orderLink, String whatsappLink) {}

    /** What was asked for and isn't in stock, for restocking decisions. */
    public record Demand(String productCode, String colour, String size, boolean inStock) {}

    /**
     * publicReply / privateReply null = don't send. handover = a person should answer (complaint, unsure, haggling).
     * urgent = tell the seller straight away (a complaint or a bad comment), not just in the morning summary.
     */
    public record Plan(String publicReply, String privateReply, boolean hideComment, boolean handover, Demand demand, boolean urgent) {
        public Plan(String publicReply, String privateReply, boolean hideComment, boolean handover, Demand demand) {
            this(publicReply, privateReply, hideComment, handover, demand, false);
        }
    }

    private CommentReplyPlanner() {}

    /** A web link or a bare domain (whatsapp.com/channel/..., bit.ly/x, www.site.co.ke). */
    private static final Pattern LINK = Pattern.compile("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|co\\.ke|ke|ly|me|net|org|io|app|link)/)");

    public static boolean hasPhoneNumber(String text) {
        return text != null && PHONE.matcher(text).find();
    }

    public static boolean hasLink(String text) {
        return text != null && LINK.matcher(text).find();
    }

    public static Plan plan(String comment, CommentReader.Reading reading, List<Item> postItems, Shop shop, Duration commentAge) {
        boolean phone = hasPhoneNumber(comment);
        boolean canDm = commentAge == null || commentAge.compareTo(PRIVATE_REPLY_WINDOW) < 0;
        boolean sw = reading == null || !"en".equalsIgnoreCase(reading.language());
        Intent intent = reading == null || reading.intent() == null ? Intent.OTHER : reading.intent();
        boolean buyer = reading != null && (reading.buyer() || BUYING.contains(intent));

        // Spam gets no reply; a number or a link in it is hidden so the seller's followers aren't drawn into a scam
        if (intent == Intent.SPAM) return new Plan(null, null, phone || hasLink(comment), false, null);
        // A complaint or a bad comment (fraud accusation, insult, mocking the work) is hidden from the public, gets no
        // public reply, and goes to the seller straight away. The commenter gets a calm private message.
        boolean negative = reading != null && reading.negative();
        if (intent == Intent.COMPLAINT || (negative && !buyer)) {
            String sorry = sw ? "Habari, pole sana kwa shida. Tumeona ujumbe wako na mwenye duka atakushughulikia sasa hivi. Tueleze kilichotokea hapa."
                    : "Hi, we're very sorry about this. We've seen your comment and the owner will deal with it right away. Tell us here what happened.";
            return new Plan(null, canDm ? sorry : null, true, true, null, true);
        }
        if (negative) {
            // A would-be buyer complaining in public ("why won't you post the price"): answer them privately as a
            // buyer, keep the complaint out of public view, and let the seller know at once
            Plan answer = plan(comment, withoutNegative(reading), postItems, shop, commentAge);
            return new Plan(null, answer.privateReply(), true, answer.handover(), answer.demand(), true);
        }
        if (!buyer) {
            if (phone) return new Plan(privacyReply(sw, canDm, shop), canDm ? greeting(sw, shop) : null, true, false, null);
            return new Plan(intent == Intent.COMPLIMENT ? (sw ? "Asante sana 💚" : "Thank you so much 💚") : null, null, false, false, null);
        }

        // ── a buyer ──
        List<Item> product = forProduct(reading.productCode(), postItems);
        String colour = lower(reading.colour());
        String size = upper(reading.size());
        boolean unsure = !"high".equalsIgnoreCase(reading.confidence());
        boolean wa = reading.wantsWhatsApp() || phone;

        if (product.isEmpty() && postItems != null && !postItems.isEmpty() && postItems.stream().allMatch(Item::service)) {
            // A salon or nail tech: the post's whole price list answers "how much" / "can I book"
            String pub = phone ? privacyReply(sw, canDm, shop)
                    : canDm ? (sw ? "Karibu! Tumekutumia DM na bei 💚" : "Welcome! We've sent you our prices in a DM 💚")
                    : (sw ? "Karibu! 💚" : "Welcome! 💚") + orderLine(sw, shop, true);
            return new Plan(pub, canDm ? productCard(sw, postItems, shop, wa) : null, phone, unsure, null);
        }
        if (product.isEmpty() && intent == Intent.CONTACT) {
            String dm = greeting(sw, shop) + whatsappLine(sw, shop, true);
            return new Plan(publicFor(sw, canDm, phone, shop, null), canDm ? dm : null, phone, false, null);
        }
        if (product.isEmpty()) {
            // No product tagged or can't tell which one: a person answers, the buyer still hears back at once
            String dm = sw ? "Habari! Asante kwa kuuliza 💚 Ni bidhaa gani ungependa? Tutumie picha au jina, tutakujibu sasa hivi."
                    : "Hi! Thanks for asking 💚 Which item would you like? Send us a photo or the name and we'll answer right away.";
            return new Plan(publicFor(sw, canDm, phone, shop, null), canDm ? dm : null, phone, true, null);
        }

        List<Item> wanted = product.stream()
                .filter(i -> colour == null || colour.equals(lower(i.colour())))
                .filter(i -> size == null || size.equals(upper(i.size())))
                .toList();
        List<Item> inStock = wanted.stream().filter(i -> i.available()).toList();
        Item first = product.get(0);
        Demand demand = colour != null || size != null
                ? new Demand(first.productCode(), colour, size, !inStock.isEmpty()) : null;

        if (intent == Intent.LOCATION && colour == null && size == null) {
            String pub = phone ? privacyReply(sw, canDm, shop)
                    : (sw ? "Tuko " + shop.location() + " 📍" : "We're at " + shop.location() + " 📍")
                    + (canDm ? (sw ? " Tumekutumia DM 💚" : " We've sent you a DM 💚") : orderLine(sw, shop));
            return new Plan(pub, canDm ? productCard(sw, product, shop, wa) : null, phone, unsure, null);
        }

        if (inStock.isEmpty()) {
            // What they asked for is out (or doesn't exist): say so, offer what is in stock
            List<Item> others = product.stream().filter(i -> i.available()).toList();
            String asked = describe(colour, size, sw);
            String pub;
            if (phone) {
                pub = privacyReply(sw, canDm, shop);
            } else if (others.isEmpty()) {
                pub = sw ? "Imeisha kwa sasa 😔 Tutakujulisha ikirudi." : "Sold out for now 😔 We'll let you know when it's back.";
            } else {
                pub = (sw ? (asked.isEmpty() ? "Hiyo imeisha" : asked + " imeisha") + ", lakini tuko na " + options(others, colour != null, sw) + "."
                        : (asked.isEmpty() ? "That one is sold out" : asked + " is sold out") + ", but we have " + options(others, colour != null, sw) + ".")
                        + (canDm ? (sw ? " Tumekutumia DM 💚" : " We've sent you a DM 💚") : orderLine(sw, shop));
            }
            String dm = others.isEmpty() ? null : productCard(sw, others, shop, wa);
            return new Plan(pub, canDm ? dm : null, phone, unsure, demand);
        }

        boolean services = inStock.stream().allMatch(Item::service);
        String opener = services ? (sw ? "Karibu! " : "Welcome! ") : (sw ? "Iko! " : "Available! ");
        String pub = phone ? privacyReply(sw, canDm, shop)
                : opener + (canDm ? (sw ? "Tumekutumia DM 💚" : "We've sent you a DM 💚") : orderLine(sw, shop, services).trim());
        return new Plan(pub, canDm ? productCard(sw, inStock, shop, wa) : null, phone, unsure, demand);
    }

    private static CommentReader.Reading withoutNegative(CommentReader.Reading r) {
        return new CommentReader.Reading(r.intent(), r.buyer(), r.wantsWhatsApp(), r.productCode(), r.colour(), r.size(),
                r.language(), r.confidence(), false);
    }

    // ───────────────────────────── wording ─────────────────────────────

    /**
     * The DM: name, price, what's in stock, where, how to order. For services, the price list and how to book.
     * wa = they asked for WhatsApp (or left a number), so the business's WhatsApp link is added.
     */
    static String productCard(boolean sw, List<Item> items, Shop shop, boolean wa) {
        Item first = items.get(0);
        boolean services = items.stream().allMatch(Item::service);
        if (services && items.stream().map(Item::name).distinct().count() > 1) {
            StringBuilder menu = new StringBuilder(sw ? "Habari! 💚 Bei zetu:" : "Hi! 💚 Our prices:");
            items.stream().filter(i -> i.price() != null)
                    .forEach(i -> menu.append("\n• ").append(i.name()).append(": KES ").append(kes(i.price())));
            if (shop.location() != null) menu.append(sw ? "\nTuko " : "\nWe're at ").append(shop.location());
            if (shop.orderLink() != null) menu.append(sw ? "\n\nBook hapa: " : "\n\nBook here: ").append(shop.orderLink());
            return menu + whatsappLine(sw, shop, wa);
        }
        BigDecimal low = items.stream().map(Item::price).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
        BigDecimal high = items.stream().map(Item::price).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
        String price = low == null ? null : low.compareTo(high) == 0 ? "KES " + kes(low) : "KES " + kes(low) + " to " + kes(high);
        List<String> colours = items.stream().filter(i -> i.available()).map(Item::colour).filter(Objects::nonNull).distinct().toList();
        List<String> sizes = items.stream().filter(i -> i.available()).map(Item::size).filter(Objects::nonNull).distinct().toList();

        StringBuilder sb = new StringBuilder(sw ? "Habari! 💚 " : "Hi! 💚 ").append(first.name());
        if (price != null) sb.append(sw ? " ni " : " is ").append(price);
        sb.append(".");
        if (!colours.isEmpty()) sb.append(sw ? "\nRangi zilizopo: " : "\nColours in stock: ").append(String.join(", ", colours));
        if (!sizes.isEmpty()) sb.append(sw ? "\nSize zilizopo: " : "\nSizes in stock: ").append(String.join(", ", sizes));
        if (shop.location() != null) sb.append(sw ? "\nTuko " : "\nWe're at ").append(shop.location());
        if (shop.orderLink() != null) {
            sb.append(services ? (sw ? "\n\nBook hapa: " : "\n\nBook here: ")
                    : (sw ? "\n\nAgiza na ulipe kwa M-Pesa hapa: " : "\n\nOrder and pay by M-Pesa here: ")).append(shop.orderLink());
        }
        return sb + whatsappLine(sw, shop, wa);
    }

    private static String publicFor(boolean sw, boolean canDm, boolean phone, Shop shop, String lead) {
        if (phone) return privacyReply(sw, canDm, shop);
        return (lead == null ? "" : lead) + (canDm ? (sw ? "Asante! Tumekutumia DM 💚" : "Thanks! We've sent you a DM 💚")
                : (sw ? "Asante! 💚" : "Thanks! 💚") + orderLine(sw, shop));
    }

    /** The comment shows a phone number: it gets hidden, so the reply mustn't repeat it. */
    private static String privacyReply(boolean sw, boolean canDm, Shop shop) {
        return sw ? "Asante! Tumeficha namba yako ili iwe salama." + (canDm ? " Tumekutumia DM 💚" : orderLine(true, shop))
                : "Thanks! We've hidden your number to keep it safe." + (canDm ? " We've sent you a DM 💚" : orderLine(false, shop));
    }

    private static String greeting(boolean sw, Shop shop) {
        return sw ? "Habari! 💚 Asante kwa kuwasiliana na " + shop.name() + ". Ungependa nini leo?"
                : "Hi! 💚 Thanks for reaching out to " + shop.name() + ". What would you like today?";
    }

    /** For comments too old for a DM: where to order (or book) instead, else the WhatsApp link. */
    private static String orderLine(boolean sw, Shop shop) {
        return orderLine(sw, shop, false);
    }

    private static String orderLine(boolean sw, Shop shop, boolean services) {
        if (shop.orderLink() != null) {
            return (services ? (sw ? " Book hapa: " : " Book here: ") : (sw ? " Agiza hapa: " : " Order here: ")) + shop.orderLink();
        }
        return shop.whatsappLink() == null ? "" : (sw ? " Tuandikie WhatsApp: " : " WhatsApp us: ") + shop.whatsappLink();
    }

    private static String whatsappLine(boolean sw, Shop shop, boolean wa) {
        if (!wa || shop.whatsappLink() == null) return "";
        return (sw ? "\nAu tuongee WhatsApp: " : "\nOr chat with us on WhatsApp: ") + shop.whatsappLink();
    }

    private static String describe(String colour, String size, boolean sw) {
        List<String> parts = new ArrayList<>();
        if (colour != null) parts.add(colour);
        if (size != null) parts.add((sw ? "size " : "size ") + size);
        String d = String.join(" ", parts);
        return d.isEmpty() ? "" : Character.toUpperCase(d.charAt(0)) + d.substring(1);
    }

    private static String options(List<Item> items, boolean byColour, boolean sw) {
        List<String> values = items.stream().map(i -> byColour ? i.colour() : i.size()).filter(Objects::nonNull).distinct().limit(5).toList();
        if (values.isEmpty()) return sw ? "rangi au size nyingine" : "other options";
        if (values.size() == 1) return values.get(0);
        return String.join(", ", values.subList(0, values.size() - 1)) + (sw ? " na " : " and ") + values.get(values.size() - 1);
    }

    private static List<Item> forProduct(String code, List<Item> postItems) {
        if (postItems == null || postItems.isEmpty()) return List.of();
        if (code != null) {
            List<Item> match = postItems.stream().filter(i -> code.equals(i.productCode())).toList();
            if (!match.isEmpty()) return match;
        }
        // One product on the post: that's the one they mean
        Set<String> codes = new LinkedHashSet<>();
        postItems.forEach(i -> codes.add(i.productCode()));
        return codes.size() == 1 ? postItems : List.of();
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String kes(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.0f", v);
    }
}
