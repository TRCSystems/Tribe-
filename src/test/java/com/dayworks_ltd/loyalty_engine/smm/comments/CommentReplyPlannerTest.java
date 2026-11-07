package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Item;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Shop;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Replies to real comments from a boutique reel ("Naweza pata hio dress ya green", "mko wapi"...). */
class CommentReplyPlannerTest {

    static final Shop SHOP = new Shop("Exquisite Collections", "Moi Avenue, Nairobi", "https://tribessystems.co.ke/o/abc123", "https://wa.me/254700000000");
    static final Duration FRESH = Duration.ofMinutes(2);

    static Item dress(String colour, String size, int stock) {
        return new Item("DRESS-1", "Satin wrap dress", colour, size, new BigDecimal("3500"), stock);
    }

    final List<Item> reel = new ArrayList<>(List.of(dress("green", "M", 2), dress("green", "L", 0), dress("black", "M", 4)));

    static Reading reading(Intent intent, String colour, String size, String language) {
        return new Reading(intent, true, false, "DRESS-1", colour, size, language, "high");
    }

    @Test
    void greenDressInStockGetsPublicReplyAndProductCardInDm() {
        Plan p = CommentReplyPlanner.plan("Naweza pata hio dress ya green", reading(Intent.VARIANT, "green", null, "sw"), reel, SHOP, FRESH);

        assertEquals("Iko! Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("Satin wrap dress ni KES 3,500"));
        assertTrue(p.privateReply().contains("Rangi zilizopo: green"));
        assertTrue(p.privateReply().contains("Size zilizopo: M"), "L is out of stock in green");
        assertTrue(p.privateReply().contains(SHOP.orderLink()));
        assertFalse(p.publicReply().contains("3,500"), "price goes in the DM, not in public");
        assertFalse(p.hideComment());
        assertFalse(p.handover());
        assertTrue(p.demand().inStock());
    }

    @Test
    void englishCommentGetsEnglishReply() {
        Plan p = CommentReplyPlanner.plan("Location please I need the green color.", reading(Intent.VARIANT, "green", null, "en"), reel, SHOP, FRESH);

        assertEquals("Available! We've sent you a DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("We're at Moi Avenue, Nairobi"));
    }

    @Test
    void soldOutColourOffersWhatIsInStockAndRecordsDemand() {
        reel.set(0, dress("green", "M", 0));

        Plan p = CommentReplyPlanner.plan("Naitaka hiyo ya green", reading(Intent.VARIANT, "green", null, "sw"), reel, SHOP, FRESH);

        assertEquals("Green imeisha, lakini tuko na black. Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("Rangi zilizopo: black"));
        assertEquals("green", p.demand().colour());
        assertFalse(p.demand().inStock());
    }

    @Test
    void locationQuestionAnswersWhereTheShopIs() {
        Plan p = CommentReplyPlanner.plan("mko wapi", reading(Intent.LOCATION, null, null, "sw"), reel, SHOP, FRESH);

        assertEquals("Tuko Moi Avenue, Nairobi 📍 Tumekutumia DM 💚", p.publicReply());
        assertNotNull(p.privateReply());
    }

    @Test
    void phoneNumberInCommentIsHiddenAndNotRepeated() {
        String comment = "Nataka size M 0702339778";
        Plan p = CommentReplyPlanner.plan(comment, reading(Intent.READY_TO_BUY, null, "M", "sw"), reel, SHOP, FRESH);

        assertTrue(p.hideComment());
        assertFalse(p.publicReply().matches(".*\\d{6,}.*"));
        assertTrue(p.publicReply().contains("Tumeficha namba yako"));
        assertNotNull(p.privateReply());
    }

    @Test
    void phoneNumberFormats() {
        assertTrue(CommentReplyPlanner.hasPhoneNumber("call 0712 345 678"));
        assertTrue(CommentReplyPlanner.hasPhoneNumber("+254712345678"));
        assertTrue(CommentReplyPlanner.hasPhoneNumber("0112345678 pls"));
        assertFalse(CommentReplyPlanner.hasPhoneNumber("size 12, KES 3500"));
        assertFalse(CommentReplyPlanner.hasPhoneNumber("order 07123456789012"));
    }

    @Test
    void commentsOlderThanSevenDaysGetNoDmButAnOrderLink() {
        Plan p = CommentReplyPlanner.plan("Naitaka hiyo sasa, mko wapi?", reading(Intent.READY_TO_BUY, null, null, "sw"), reel, SHOP, Duration.ofDays(21));

        assertNull(p.privateReply());
        assertTrue(p.publicReply().startsWith("Iko!"));
        assertTrue(p.publicReply().contains(SHOP.orderLink()));
    }

    @Test
    void complimentGetsThanksOnly() {
        Plan p = CommentReplyPlanner.plan("😍😍 beautiful", new Reading(Intent.COMPLIMENT, false, false, null, null, null, "en", "high"), reel, SHOP, FRESH);

        assertEquals("Thank you so much 💚", p.publicReply());
        assertNull(p.privateReply());
        assertFalse(p.handover());
    }

    @Test
    void unclearProductOnMultiProductPostGoesToStaff() {
        reel.add(new Item("BAG-2", "Leather tote", "brown", null, new BigDecimal("2800"), 3));

        Plan p = CommentReplyPlanner.plan("shingapi", new Reading(Intent.PRICE, true, false, null, null, null, "sw", "low"), reel, SHOP, FRESH);

        assertTrue(p.handover());
        assertEquals("Asante! Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("Ni bidhaa gani"));
    }

    @Test
    void complaintGoesToStaffAndSpamIsIgnored() {
        Plan complaint = CommentReplyPlanner.plan("Nililipa wiki iliyopita sijapata", new Reading(Intent.COMPLAINT, false, false, null, null, null, "sw", "high"),
                reel, SHOP, FRESH);
        assertTrue(complaint.handover());
        assertTrue(complaint.urgent(), "the seller hears about a complaint straight away");
        assertTrue(complaint.hideComment(), "a complaint is hidden from the public");
        assertNull(complaint.publicReply(), "no public reply under a hidden complaint");
        assertTrue(complaint.privateReply().startsWith("Habari, pole sana"));

        Plan spam = CommentReplyPlanner.plan("Earn 50k daily click here", new Reading(Intent.SPAM, false, false, null, null, null, "en", "high"), reel, SHOP, FRESH);
        assertNull(spam.publicReply());
        assertNull(spam.privateReply());
    }

    static Reading read(Intent i) {
        return new Reading(i, true, false, null, null, null, "sw", "high");
    }

    @Test
    void afterSalesGoesToTheSellerAtOnceWithACalmReply() {
        Plan p = CommentReplyPlanner.plan("Nilibuy akakata", new Reading(Intent.AFTER_SALES, false, false, null, null, null, "sw", "high"), reel, SHOP, FRESH);
        assertEquals("Tunashughulikia 🙏 Tumekutumia DM.", p.publicReply());
        assertTrue(p.privateReply().contains("namba ya oda"));
        assertTrue(p.handover());
        assertTrue(p.urgent());
        assertFalse(p.hideComment(), "a customer asking for help is not hidden; only bad comments are");
    }

    @Test
    void businessLeadIsThankedAndHandedToTheSeller() {
        Plan p = CommentReplyPlanner.plan("Mnauza wholesale? Nataka kuwa agent Arusha", read(Intent.BUSINESS_LEAD), reel, SHOP, FRESH);
        assertEquals("Asante! Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("bei za jumla"));
        assertTrue(p.handover());
        assertFalse(p.urgent());
    }

    @Test
    void qualityDoubtGetsTheFactsAndTheSellerAnswersTheRest() {
        Plan p = CommentReplyPlanner.plan("Hii ni original kweli?", read(Intent.QUALITY_DOUBT), GOWN, SHOP, FRESH);
        assertEquals("Swali zuri 🙏 Tumekutumia DM.", p.publicReply());
        assertTrue(p.privateReply().contains("KES 12,500"));
        assertTrue(p.privateReply().contains("kuhusu ubora"));
        assertTrue(p.handover(), "the agents never claim it is original: the seller does");
    }

    @Test
    void adviceOnASalonPostSendsThePriceListAndTheSellerAdvises() {
        Plan p = CommentReplyPlanner.plan("Nina 2000 nifanye nini kwa nails?", read(Intent.ADVICE), NAILS, SHOP, FRESH);
        assertTrue(p.privateReply().contains("Acrylic extensions: KES 2,500"));
        assertTrue(p.privateReply().contains("atakushauri"));
        assertTrue(p.handover());
    }

    @Test
    void setListsEveryPieceAndTheSellerConfirmsTheSetPrice() {
        List<Item> outfit = List.of(
                new Item("TOP-1", "Linen top", "white", "M", new BigDecimal("1800"), 3),
                new Item("TOP-1", "Linen top", "white", "L", new BigDecimal("1800"), 0),
                new Item("PANTS-1", "Wide pants", "white", "M", new BigDecimal("2200"), 2));
        Plan p = CommentReplyPlanner.plan("Complete attire ngapi zote?", read(Intent.SET), outfit, SHOP, FRESH);
        assertTrue(p.privateReply().contains("• Linen top: KES 1,800"));
        assertTrue(p.privateReply().contains("• Wide pants: KES 2,200"));
        assertTrue(p.privateReply().contains("bei ya set nzima"));
        assertTrue(p.handover());
    }

    @Test
    void newIntentsCountAsBuyersForTheSecondLookRule() {
        for (String i : List.of("SET", "ADVICE", "QUALITY_DOUBT", "BUSINESS_LEAD")) {
            assertFalse(CommentReader.doubtful(i, false, "high", "x"), i + " is already a buyer: no second look");
        }
    }

    @Test
    void fraudAccusationIsHiddenAndTheSellerAlertedAtOnce() {
        Reading bad = new Reading(Intent.OTHER, false, false, null, null, null, "sw", "high", true);
        Plan p = CommentReplyPlanner.plan("Hawa ni matapeli msinunue", bad, reel, SHOP, FRESH);

        assertTrue(p.hideComment());
        assertTrue(p.urgent());
        assertTrue(p.handover());
        assertNull(p.publicReply());
        assertNotNull(p.privateReply(), "the commenter still gets a calm private message");
    }

    @Test
    void annoyedBuyerIsHiddenButStillGetsThePriceInPrivate() {
        Reading annoyed = new Reading(Intent.PRICE, true, false, null, null, null, "en", "high", true);
        Plan p = CommentReplyPlanner.plan("How is it difficult to put the price instead of telling everyone to WhatsApp you", annoyed, GOWN, SHOP, FRESH);

        assertTrue(p.hideComment());
        assertTrue(p.urgent());
        assertNull(p.publicReply());
        assertTrue(p.privateReply().contains("KES 12,500"), "they asked the price: they get it, privately");
    }

    @Test
    void oldBadCommentIsStillHiddenAndReportedThoughNoDmIsAllowed() {
        Plan p = CommentReplyPlanner.plan("Nimeletewa kitu mbaya", new Reading(Intent.COMPLAINT, false, false, null, null, null, "sw", "high"),
                reel, SHOP, Duration.ofDays(20));
        assertTrue(p.hideComment());
        assertTrue(p.urgent());
        assertNull(p.privateReply());
        assertNull(p.publicReply());
    }

    @Test
    void ordinaryCommentsAreNotUrgent() {
        assertFalse(CommentReplyPlanner.plan("shingapi", new Reading(Intent.PRICE, true, false, null, null, null, "sw", "high"), GOWN, SHOP, FRESH).urgent());
        assertFalse(CommentReplyPlanner.plan("😍", new Reading(Intent.COMPLIMENT, false, false, null, null, null, "en", "high"), GOWN, SHOP, FRESH).urgent());
    }

    @Test
    void spamWithALinkIsHiddenAndNotAnswered() {
        Reading spam = new Reading(Intent.SPAM, false, false, null, null, null, "en", "high");
        Plan link = CommentReplyPlanner.plan("https://whatsapp.com/channel/0029VbE2HK7 Night Movie Like", spam, reel, SHOP, FRESH);
        assertTrue(link.hideComment());
        assertNull(link.publicReply());
        assertNull(link.privateReply());

        Plan bait = CommentReplyPlanner.plan("Come to my private chat so we can talk.", spam, reel, SHOP, FRESH);
        assertFalse(bait.hideComment(), "no link or number: left alone, just not answered");
        assertNull(bait.publicReply());
    }

    @Test
    void fancyFontLettersBecomePlainBeforeReading() {
        assertEquals("What name og that liquid", CommentReader.plain("𝚆𝚑𝚊𝚝 𝚗𝚊𝚖𝚎 𝚘𝚐 𝚝𝚑𝚊𝚝 𝚕𝚒𝚚𝚞𝚒𝚍"));
        assertEquals("How much", CommentReader.plain("𝐇𝐨𝐰 𝐦𝐮𝐜𝐡"));
    }

    @Test
    void readerPromptListsOnlyThePostsProducts() {
        String prompt = CommentReader.prompt(List.of(new CommentReader.PostProduct("DRESS-1", "Satin wrap dress", "green, black", "M, L")));
        assertTrue(prompt.contains("DRESS-1 | Satin wrap dress | green, black | M, L"));
        assertTrue(prompt.contains("shingapi"));
        assertTrue(prompt.contains("kijani = green"));
    }

    // ── Tridah Collection reel and a salon post ──

    static final List<Item> GOWN = List.of(new Item("GOWN-1", "Silver lace mermaid gown", "silver", "M", new BigDecimal("12500"), 1));

    @Test
    void shingapiHiiOnASingleProductReel() {
        Plan p = CommentReplyPlanner.plan("Shingapi hii", new Reading(Intent.PRICE, true, false, null, null, null, "sw", "high"), GOWN, SHOP, FRESH);

        assertEquals("Iko! Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("Silver lace mermaid gown ni KES 12,500"));
        assertFalse(p.privateReply().contains("WhatsApp"), "only offered when asked for");
    }

    @Test
    void whatsappRequestWithNumberIsHiddenAndAnsweredWithWhatsappLink() {
        String comment = "Inapatikana wp dada shingapi 0702339778";
        Plan p = CommentReplyPlanner.plan(comment, new Reading(Intent.PRICE, true, true, null, null, null, "sw", "high"), GOWN, SHOP, Duration.ofDays(2));

        assertTrue(p.hideComment());
        assertFalse(p.publicReply().contains("0702339778"));
        assertTrue(p.privateReply().contains("KES 12,500"));
        assertTrue(p.privateReply().contains("Au tuongee WhatsApp: https://wa.me/254700000000"));
    }

    @Test
    void flirtingIsTreatedAsACompliment() {
        Plan p = CommentReplyPlanner.plan("Hello baby why do look like queen 😂❤️",
                new Reading(Intent.COMPLIMENT, false, false, null, null, null, "en", "high"), GOWN, SHOP, FRESH);
        assertEquals("Thank you so much 💚", p.publicReply());
        assertNull(p.privateReply());
    }

    static final List<Item> NAILS = List.of(
            new Item("GEL", "Gel polish", null, null, new BigDecimal("1500"), null),
            new Item("ACRYLIC", "Acrylic extensions", null, null, new BigDecimal("2500"), null),
            new Item("PEDI", "Spa pedicure", null, null, new BigDecimal("1800"), null));

    @Test
    void salonBookingQuestionGetsThePriceListAndBookingLink() {
        Plan p = CommentReplyPlanner.plan("mko free Saturday? how much for acrylics",
                new Reading(Intent.BOOKING, true, false, null, null, null, "en", "high"), NAILS, SHOP, FRESH);

        assertEquals("Welcome! We've sent you our prices in a DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("• Acrylic extensions: KES 2,500"));
        assertTrue(p.privateReply().contains("Book here: " + SHOP.orderLink()));
        assertFalse(p.handover());
    }

    @Test
    void salonServiceNamedDirectly() {
        Plan p = CommentReplyPlanner.plan("gel shingapi", new Reading(Intent.PRICE, true, false, "GEL", null, null, "sw", "high"), NAILS, SHOP, FRESH);

        assertEquals("Karibu! Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("Gel polish ni KES 1,500"));
        assertTrue(p.privateReply().contains("Book hapa: "));
    }

    @Test
    void contactOnlyGetsWhatsappLinkInDm() {
        List<Item> twoProducts = List.of(GOWN.get(0), new Item("BAG-2", "Leather tote", "brown", null, new BigDecimal("2800"), 3));
        Plan p = CommentReplyPlanner.plan("wp", new Reading(Intent.CONTACT, true, true, null, null, null, "sw", "high"), twoProducts, SHOP, FRESH);

        assertEquals("Asante! Tumekutumia DM 💚", p.publicReply());
        assertTrue(p.privateReply().contains("https://wa.me/254700000000"));
        assertFalse(p.handover());
    }
}
