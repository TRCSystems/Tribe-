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
            Intent.VARIANT, Intent.READY_TO_BUY, Intent.BOOKING, Intent.CONTACT,
            Intent.SET, Intent.ADVICE, Intent.QUALITY_DOUBT, Intent.BUSINESS_LEAD);
    static final Set<String> BUYING_NAMES = BUYING.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** A buyer as the planner decides: the buyer flag, or any buying intent. */
    public static boolean isBuyer(CommentReader.Reading r) {
        return r != null && (r.buyer() || (r.intent() != null && BUYING.contains(r.intent())));
    }

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

    /**
     * The business's answers that don't depend on the product. orderLink orders goods or books services. landmark is
     * how to find the shop ("Tom Mboya Street, next to ..."); hours are the opening hours as the seller wrote them.
     */
    public record Shop(String name, String location, String orderLink, String whatsappLink, String landmark, String hours) {
        public Shop(String name, String location, String orderLink, String whatsappLink) {
            this(name, location, orderLink, whatsappLink, null, null);
        }

        public Shop {
            location = blankToNull(location);
            landmark = blankToNull(landmark);
            hours = blankToNull(hours);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

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
        // Swahili for Swahili or Sheng (and when unsure); English otherwise, including French, Portuguese and other
        // languages until the Writer answers in the buyer's own language
        boolean sw = reading == null || reading.language() == null || "sw".equalsIgnoreCase(reading.language());
        Intent intent = reading == null || reading.intent() == null ? Intent.OTHER : reading.intent();
        boolean buyer = reading != null && (reading.buyer() || BUYING.contains(intent));

        // Spam gets no reply and is hidden, link or not ("check your hidden messages", betting bait), so the seller's
        // followers aren't drawn into a scam under the seller's own post
        if (intent == Intent.SPAM) return new Plan(null, null, true, false, null);
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
        if (intent == Intent.AFTER_SALES) {
            // Someone who already bought: calm reply, the seller is told at once
            String dm = sw ? "Habari! Asante kwa kununua kwetu 💚 Tueleze kilichotokea (na jina au namba ya oda yako), mwenye duka atakushughulikia sasa hivi."
                    : "Hi! Thanks for buying from us 💚 Tell us what happened (and your name or order number) and the owner will sort it out right away.";
            String pub = phone ? privacyReply(sw, canDm, shop)
                    : canDm ? (sw ? "Tunashughulikia 🙏 Tumekutumia DM." : "We're on it 🙏 We've sent you a DM.")
                    : (sw ? "Tunashughulikia 🙏" : "We're on it 🙏") + whatsappOnly(sw, shop);
            return new Plan(pub, canDm ? dm : null, phone, true, null, true);
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

        if (intent == Intent.BUSINESS_LEAD) {
            // Wholesale, agents, resellers: thank them; the seller takes it from here
            String dm = sw ? "Habari! Asante kwa kutaka kufanya kazi nasi 💚 Mwenye duka atakuandikia kuhusu bei za jumla na jinsi ya kushirikiana."
                    : "Hi! Thanks for wanting to work with us 💚 The owner will message you about wholesale prices and how we can work together.";
            String ask = sw ? "\n\nUko wapi, na ungehitaji vipande vingapi?" : "\n\nWhere are you based, and roughly how many pieces would you need?";
            return new Plan(publicFor(sw, canDm, phone, shop, null), canDm ? dm + whatsappLine(sw, shop, true) + ask : null, phone, true, null);
        }
        if (intent == Intent.QUALITY_DOUBT || intent == Intent.ADVICE) {
            // Facts from the catalogue now; the judgement call (is it original, which one suits you) is the seller's
            List<Item> facts = !product.isEmpty() ? product : postItems == null ? List.of() : postItems;
            String card = facts.isEmpty() ? greeting(sw, shop) : productCard(sw, facts, shop, wa);
            String more = intent == Intent.QUALITY_DOUBT
                    ? (sw ? "\n\nMwenye duka atakujibu kuhusu ubora sasa hivi." : "\n\nThe owner will answer you about the quality right away.")
                    : (sw ? "\n\nMwenye duka atakushauri sasa hivi." : "\n\nThe owner will advise you right away.");
            String pub = phone ? privacyReply(sw, canDm, shop)
                    : canDm ? (sw ? "Swali zuri 🙏 Tumekutumia DM." : "Good question 🙏 We've sent you a DM.")
                    : (sw ? "Swali zuri 🙏" : "Good question 🙏") + orderLine(sw, shop);
            return new Plan(pub, canDm ? card + more : null, phone, true, null);
        }
        if (intent == Intent.SET && postItems != null && !postItems.isEmpty()) {
            // Every piece on the post with its price; the seller confirms the set price (sets are priced per seller)
            List<Item> pieces = postItems.stream().filter(Item::available).toList();
            String dm = (pieces.isEmpty() ? (sw ? "Habari! Vipande vya set hii vimeisha kwa sasa." : "Hi! The pieces of this set are sold out for now.")
                    : menu(sw, pieces)) + (sw ? "\n\nTutakuthibitishia bei ya set nzima sasa hivi." : "\n\nWe'll confirm the price for the full set right away.")
                    + whatsappLine(sw, shop, wa)
                    + (sw ? "\n\nUngependa set nzima au kipande kimoja?" : "\n\nWould you like the full set or one piece?");
            return new Plan(publicFor(sw, canDm, phone, shop, null), canDm ? dm : null, phone, true, null);
        }

        if (product.isEmpty() && postItems != null && !postItems.isEmpty() && postItems.stream().allMatch(Item::service)) {
            // A salon or nail tech: the post's whole price list answers "how much" / "can I book"
            String pub = phone ? privacyReply(sw, canDm, shop)
                    : canDm ? (sw ? "Karibu! Tumekutumia DM na bei 💚" : "Welcome! We've sent you our prices in a DM 💚")
                    : (sw ? "Karibu! 💚" : "Welcome! 💚") + orderLine(sw, shop, true);
            return new Plan(pub, canDm ? productCard(sw, postItems, shop, wa) : null, phone, unsure, null);
        }
        if (product.isEmpty() && intent == Intent.LOCATION) {
            // "Where are you located" on a post with no product tagged: the answer is where the shop is, not a product
            if (shop.location() == null) {
                // The seller hasn't said where they are: a person answers, the buyer still hears back at once
                String dm = sw ? "Habari! Asante kwa kuuliza 💚 Mwenye duka atakutumia mahali tulipo sasa hivi."
                        : "Hi! Thanks for asking 💚 The owner will send you our exact location right away.";
                return new Plan(publicFor(sw, canDm, phone, shop, null), canDm ? dm : null, phone, true, null);
            }
            String pub = phone ? privacyReply(sw, canDm, shop)
                    : (sw ? "Tuko " : "We're at ") + shop.location() + " 📍"
                    + (canDm ? (sw ? " Tumekutumia DM 💚" : " We've sent you a DM 💚") : orderLine(sw, shop));
            return new Plan(pub, canDm ? whereCard(sw, shop, wa) : null, phone, unsure, null);
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

        if (intent == Intent.LOCATION && colour == null && size == null && shop.location() != null) {
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
                r.language(), r.confidence(), false, r.criticism(), r.place(), r.sizes(), r.friendTagged(), r.answeredByOther());
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
            return menu + whatsappLine(sw, shop, wa) + closingQuestion(sw, items);
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
        return sb + whatsappLine(sw, shop, wa) + closingQuestion(sw, items);
    }

    /**
     * Meta allows one private reply per comment: the agent cannot follow up unless the buyer writes back. So the DM
     * ends with one easy question that is natural to answer, and the answer opens a normal chat. It asks about what is
     * actually open: which colour (when there is a choice), which size, which service and day, or where to deliver.
     */
    static String closingQuestion(boolean sw, List<Item> items) {
        List<Item> open = items.stream().filter(Item::available).toList();
        if (open.isEmpty()) open = items;
        if (open.stream().allMatch(Item::service)) {
            long names = open.stream().map(Item::name).filter(Objects::nonNull).distinct().count();
            if (names > 1) {
                return sw ? "\n\nUngependa kufanya ipi, na ungependa kuja lini?" : "\n\nWhich one would you like, and when would you like to come in?";
            }
            return sw ? "\n\nUngependa kuja siku gani?" : "\n\nWhich day would you like to come in?";
        }
        List<String> colours = open.stream().map(Item::colour).filter(Objects::nonNull).distinct().toList();
        if (colours.size() > 1) {
            String two = colours.get(0) + (sw ? " au " : " or ") + colours.get(1);
            return sw ? "\n\nUngependa rangi gani, " + two + "?" : "\n\nWhich colour would you like, " + two + "?";
        }
        List<String> sizes = open.stream().map(Item::size).filter(Objects::nonNull).distinct().toList();
        if (sizes.size() > 1) return sw ? "\n\nUnavaa size gani?" : "\n\nWhich size do you need?";
        return sw ? "\n\nTukuletee? Uko wapi?" : "\n\nShall we deliver it? Which area are you in?";
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

    /**
     * The DM for "where are you" when no product is in question: where, how to find it, when it's open, then one easy
     * question about what they want so the answer opens a chat.
     */
    static String whereCard(boolean sw, Shop shop, boolean wa) {
        StringBuilder sb = new StringBuilder(sw ? "Habari! 💚 Tuko " : "Hi! 💚 We're at ").append(shop.location());
        if (shop.landmark() != null) sb.append(", ").append(shop.landmark());
        sb.append(".");
        if (shop.hours() != null) sb.append(sw ? "\nTuko wazi: " : "\nOpen: ").append(shop.hours());
        if (shop.orderLink() != null) sb.append(sw ? "\n\nAgiza hapa: " : "\n\nOrder here: ").append(shop.orderLink());
        sb.append(whatsappLine(sw, shop, wa || shop.orderLink() == null));
        return sb + (sw ? "\n\nUnatafuta bidhaa gani? Tutumie picha au jina, tukujibu bei."
                : "\n\nWhich item are you looking for? Send us a photo or the name and we'll tell you the price.");
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

    /** One line per piece on the post, lowest price each: for sets and outfits. */
    static String menu(boolean sw, List<Item> items) {
        StringBuilder sb = new StringBuilder(sw ? "Habari! 💚 Vipande vya set hii:" : "Hi! 💚 The pieces in this set:");
        Map<String, Item> cheapest = new LinkedHashMap<>();
        for (Item i : items) {
            Item seen = cheapest.get(i.productCode());
            if (seen == null || (i.price() != null && (seen.price() == null || i.price().compareTo(seen.price()) < 0))) cheapest.put(i.productCode(), i);
        }
        cheapest.values().forEach(i -> sb.append("\n• ").append(i.name()).append(i.price() == null ? "" : ": KES " + kes(i.price())));
        return sb.toString();
    }

    private static String whatsappOnly(boolean sw, Shop shop) {
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
