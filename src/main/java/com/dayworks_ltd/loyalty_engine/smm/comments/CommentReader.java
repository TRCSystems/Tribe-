package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reads one Facebook / Instagram / TikTok comment (Swahili, Sheng or English) and says what the commenter wants,
 * matched against the products tagged on that post. It only labels; what to reply is decided by
 * {@link CommentReplyPlanner} from catalogue data, so prices and stock are never made up.
 *
 * <p>Two-step reading: the typed-list model (Claude Haiku) reads every comment. When it says "not a buyer" in a
 * doubtful way, {@code smm.reader.second-model} (e.g. Claude Sonnet) takes a second look and its answer is used.
 * A buyer is never sent for a second look, so this can only catch more sales. In the Reader exam (975 labelled
 * comments) buyers caught went from 95.0% to 98.3% with about a quarter of comments re-read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentReader {

    /** Stronger model for doubtful "not a buyer" readings; empty = one model only. */
    @Value("${smm.reader.second-model:}")
    private String secondModel = "";

    private static final Pattern QUESTION_WORD = Pattern.compile("(?i).*\\b(wapi|wp|where|how|ngapi|bei)\\b.*");

    /** What the commenter wants. */
    public enum Intent {
        PRICE, LOCATION, AVAILABILITY, VARIANT, READY_TO_BUY, BOOKING, CONTACT, COMPLIMENT, COMPLAINT, SPAM, OTHER,
        /** Doubts the quality or that it is genuine: "is this original", "fake materials", "inadumu?". */
        QUALITY_DOUBT,
        /** Wants to resell or work with the seller: wholesale, agent, commission, "give me job". */
        BUSINESS_LEAD,
        /** Asks the seller to advise: "budget 1,000-1,500 for 5 devices", "works on solar?", "size gani inanifaa". */
        ADVICE,
        /** Already bought and needs help: delivery not yet, exchange, return, warranty, "nilibuy akakata". */
        AFTER_SALES,
        /** Asks about the whole set or outfit: "complete attire", "ngapi zote", "bila fimbo" (without one piece). */
        SET
    }

    public record Reading(
            @JsonPropertyDescription("PRICE (how much / shingapi / bei), LOCATION (where are you / mko wapi), AVAILABILITY (do you have it / iko), "
                    + "VARIANT (asks for a colour or size: 'hio ya green', 'size 12 iko'), READY_TO_BUY (wants it now: naitaka, nataka, I'll take it, "
                    + "how do I order), BOOKING (wants an appointment or slot for a service: book, appointment, mko free, naweza kuja kesho), "
                    + "CONTACT (only asks to be reached: wp, WhatsApp, inbox, DM me, namba yako), COMPLIMENT (praise, flirting or jokes only), COMPLAINT (unhappy customer), SPAM (unrelated, links, promotions), "
                    + "QUALITY_DOUBT (is it original, fake, will it last), BUSINESS_LEAD (wholesale, agent, commission, resell, job), "
                    + "ADVICE (asks what to choose or whether it suits their need), AFTER_SALES (already bought: delivery, exchange, return, warranty, broke), "
                    + "SET (the whole set or outfit, all pieces together, or the set without one piece), OTHER")
            Intent intent,
            @JsonPropertyDescription("True when the person shows any interest in buying (asking price, location, stock, colour, size, how to order)")
            boolean buyer,
            @JsonPropertyDescription("True when they ask to continue on WhatsApp (wp, whatsapp, wats app, nitumie kwa whatsapp) or leave a number to be called")
            boolean wantsWhatsApp,
            @JsonPropertyDescription("Code of the product or service on this post they mean, only when it is clear (or the post has a single product); otherwise null")
            String productCode,
            @JsonPropertyDescription("Colour asked for, in English lower case (green, black, maroon...); otherwise null")
            String colour,
            @JsonPropertyDescription("Size asked for exactly as a size label (S, M, L, XL, 8, 10, 12, 42...); otherwise null")
            String size,
            @JsonPropertyDescription("sw when the comment is mostly Swahili or Sheng, en when mostly English")
            String language,
            @JsonPropertyDescription("high = clear meaning; low = unsure what they want or which product")
            String confidence,
            @JsonPropertyDescription("True for a bad comment the public should not see: a customer complaint (paid but nothing came, "
                    + "wrong or poor item, rude service), accusing the seller of fraud or theft (wezi, conman, scam, matapeli), insults, "
                    + "or mocking the seller's work. A fair question or mild doubt ('is it original?', 'does it last?') is not negative.")
            boolean negative) {

        /** A reading without the bad-comment flag (most callers and tests). */
        public Reading(Intent intent, boolean buyer, boolean wantsWhatsApp, String productCode, String colour, String size,
                       String language, String confidence) {
            this(intent, buyer, wantsWhatsApp, productCode, colour, size, language, confidence, false);
        }
    }

    /** A product tagged on the post, as shown to the reader. */
    public record PostProduct(String code, String name, String colours, String sizes) {}

    private final ShoppingListReader claude;

    public boolean isConfigured() {
        return claude.isConfigured();
    }

    public Reading read(String comment, List<PostProduct> products) {
        String system = prompt(products), text = "Comment: " + plain(comment);
        Reading first = claude.readText(system, text, Reading.class);
        if (secondModel == null || secondModel.isBlank() || first == null
                || !doubtful(String.valueOf(first.intent()), first.buyer(), first.confidence(), comment)) {
            return first;
        }
        try {
            Reading second = claude.readText(system, text, Reading.class, secondModel);
            if (second == null) return first;
            log.info("Comment re-read by {}: {} -> {}", secondModel, first.intent(), second.intent());
            return second;
        } catch (Exception e) {
            // The first reading stands; a failed second look must never lose the comment
            log.warn("Second look by {} failed, keeping the first reading: {}", secondModel, e.getMessage());
            return first;
        }
    }

    /**
     * A "not a buyer" reading worth a second look: other, complaint, unsure, or praise that asks a question.
     * Buyers, plain praise and spam are never re-read.
     */
    static boolean doubtful(String intent, boolean buyer, String confidence, String comment) {
        if (buyer || CommentReplyPlanner.BUYING_NAMES.contains(intent)) return false;
        if ("low".equalsIgnoreCase(confidence)) return true;
        return switch (String.valueOf(intent)) {
            case "OTHER", "COMPLAINT" -> true;
            case "COMPLIMENT" -> comment != null && (comment.contains("?") || QUESTION_WORD.matcher(comment.toLowerCase(Locale.ROOT)).matches());
            default -> false;
        };
    }

    /** Fancy-font letters people use to stand out (𝚆𝚑𝚊𝚝, 𝐇𝐨𝐰 𝐦𝐮𝐜𝐡) become ordinary letters before reading. */
    static String plain(String comment) {
        return comment == null ? "" : java.text.Normalizer.normalize(comment, java.text.Normalizer.Form.NFKC);
    }

    static String prompt(List<PostProduct> products) {
        StringBuilder sb = new StringBuilder("""
                You read comments left on a Kenyan business's Facebook, Instagram or TikTok post and say what the commenter wants, so the business can answer buyers fast. The business may sell goods (a boutique) or services (a salon, nail tech, barber, make-up artist).

                People write in English, Swahili or Sheng, often mixed, with spelling mistakes and no punctuation. Common buying phrases:
                - Price: "shingapi", "ni ngapi", "bei gani", "how much", "hm", "price pls", "pm price".
                - Location and delivery: "mko wapi", "mnapatikana wapi", "location", "where are you located", "where would I get", "which side are you selling", "mnadeliver", "free delivery ama", "itafika Kisumu?", "nilete kesho", "I am in Mozambique", or just a town or country name ("Kakamega", "Zambia"). A buyer telling you where they are wants to know if you deliver there.
                - Availability: "iko", "bado iko", "mnayo", "available?", "in stock?".
                - Colour or size: "hio ya green", "nataka ya black", "size 12 iko", "XL?", "ya maroon", "sizes", "kuna short ones", "tutapata refu kidogo?", "mikono fupi".
                - Short comments count: "Number" or "give us number" is CONTACT; "Link" on its own wants the order link (READY_TO_BUY); "Wa" on its own means WhatsApp me (CONTACT, wantsWhatsApp); "plug me", "connect us", "we need him/her" about the seller or the person in the video is CONTACT; asking the name of the shop or salon is LOCATION (they want to find it); "Sizes" is VARIANT; "hamuna ..." (don't you have ...) is AVAILABILITY; "how do you find it" or "how to find" means how do I get it (READY_TO_BUY); at a salon or nail tech "I want to learn" is BOOKING (a class).
                When a short comment asks anything about the product, its price, size, stock, place, delivery or contact, it comes from a buyer: buyer true. Only praise, jokes, chat between other people, tags and spam are not buyers.
                - Ready to buy: "naitaka", "nataka hii", "naweza pata", "I'll take it", "how do I order", "nitumie", "interested", "I'm interested", "I need it", "I need one", "need", "nahitaji", "naomba hiyo", "for me please", "napataje", "ntapata aje", "naeza pata aje", "how can I get it", and asking for it to be sent or delivered somewhere ("niko Kisumu nataka", "niko tz napataje", "send it to Nigeria").
                - Booking a service: "naweza book", "appointment", "mko free Saturday", "naweza kuja kesho", "how much for gel/acrylics/braids", "mnafanya house calls".
                - Contact: "whatsapp", "app number", "inbox", "dm", "nitumie namba", "your number", or a phone number left in the comment. Set wantsWhatsApp when they ask for WhatsApp or a number, or leave a number; if they also ask price or stock, the intent is that question, not CONTACT.
                - "wp" is ambiguous. It is usually "wapi" (where): "iko wp", "mko wp", "uko wp", "duka liko wp", "wap iyo", "wpi" -> LOCATION, wantsWhatsApp false. It means WhatsApp only when it is about chatting or a number: "wp number", "nitumie wp", "inbox wp", "wp yako", "inapatikana wp" (can I get you on WhatsApp).
                - Questions about the product itself show buying interest (buyer true): does it stretch ("inastrenji"), 5G or 4G, two-piece or one, the material, how a size fits, warranty, are you open today. Use VARIANT for size, colour or fit, AVAILABILITY for stock or opening, otherwise OTHER.
                - Someone criticising the seller for not answering or not posting the price ("mnapost hamjibu", "put the price") is a would-be buyer: buyer true, PRICE if they want the price, otherwise OTHER.
                "Inapatikana" means "is it available"; "dada", "sis", "mrembo" are friendly ways of addressing the seller, not products.
                - Quality doubt: "is this original", "si fake?", "fake materials", "inadumu?", "hazina base", "quality iko aje". A fair doubt is QUALITY_DOUBT with buyer true; an accusation of fraud is negative (see bad comments).
                - Business lead: "mnauza wholesale", "weka agent Arusha", "nataka kuuza", "commission", "supply", "give me job", "can I be your distributor". buyer true.
                - Advice: "budget 1,000-1,500 for 5 devices", "works on solar?", "size gani inanifaa", "which colour suits me", "what do you recommend". buyer true.
                - After sales, from someone who already bought: "nimeorder jana bado", "nilibuy akakata", "can I exchange", "warranty", "return". Use COMPLAINT instead when they are angry or say they were cheated.
                - Set: "complete attire", "the whole outfit", "ngapi zote", "set nzima", "3 in 1 price", "bila fimbo" (the set without one piece). buyer true.
                A comment can ask several things; pick the strongest (COMPLAINT > AFTER_SALES > SET > READY_TO_BUY > BOOKING > VARIANT > AVAILABILITY > PRICE > LOCATION > ADVICE > QUALITY_DOUBT > BUSINESS_LEAD > CONTACT).
                Emojis alone, "😍", "beautiful", "nice", "🔥", "iko sawa", "safi", "poa", flirting or jokes about the model ("why do you look like a queen", "you're pretty, where are you?", "naeza pata uyu mrembo") are COMPLIMENT, buyer false. Tagging a friend with no question is OTHER.
                Bad comments: set negative true for a customer complaint ("nililipa sijapata", "nimeletewa kitu mbaya"), accusing the seller of fraud or theft ("hawa ni wezi", "matapeli", "conman", "scam"), insults, or mocking the seller's work ("you suck at your job", "zimeharibika"). A complaint from someone who bought is COMPLAINT. Someone who has not bought but is criticising keeps their real intent (a would-be buyer annoyed that nobody answers is still PRICE or OTHER with buyer true). Fair questions or mild doubts ("is it original?", "inadumu?") are not negative.
                SPAM is anyone pulling people away to their own thing, not asking about this seller's product: "come to my private chat", "check your inbox / hidden messages", "write to me", "inbox me for an offer", links to other channels, groups or apps (betting, loans, forex, "earn 50k daily", "download ..."), "I won 17m on SportPesa" bait, bot-like messages in other alphabets asking to be answered (for example Russian "Написал Вам... Проверьте скрытые"), and religious quotes or long stories unrelated to the product. Spam is never CONTACT and never a buyer, even when it mentions chat, inbox or a number. The difference: a buyer wants the seller to contact them about this product ("inbox me the price", "nitumie namba"); a spammer invites people to their own chat, link or offer.
                Colour words: kijani = green, nyekundu = red, nyeusi = black, nyeupe = white, bluu = blue, njano = yellow, pinki = pink.
                Never guess a product code that isn't in the list below.

                Products or services on this post (code | name | colours | sizes):
                """);
        if (products.isEmpty()) sb.append("(none tagged)\n");
        for (PostProduct p : products) {
            sb.append(p.code()).append(" | ").append(p.name()).append(" | ")
                    .append(Objects.toString(p.colours(), "")).append(" | ")
                    .append(Objects.toString(p.sizes(), "")).append('\n');
        }
        return sb.toString();
    }
}
