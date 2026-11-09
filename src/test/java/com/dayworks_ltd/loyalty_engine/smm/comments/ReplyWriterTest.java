package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The Writer puts the planner's facts in the seller's voice; the Checker makes sure nothing was made up. */
class ReplyWriterTest {

    ShoppingListReader claude = mock(ShoppingListReader.class);
    ReplyWriter writer = new ReplyWriter(claude);

    Plan plan = new Plan("Iko! Tumekutumia DM 💚",
            "Habari! 💚 Maxi dress ni KES 1,500.\nRangi zilizopo: black, red\nTuko Moi Avenue\n\nUngependa rangi gani, black au red?",
            false, false, null);
    ReplyWriter.Voice voice = new ReplyWriter.Voice("Clad Hub", "dada, warm", "Iko dear 😍 tuma location tukuletee",
            "free delivery\noriginal", List.of("Mnadeliver Kisumu? → Yes, KES 300 by G4S"), "Location: Moi Avenue.");
    ReplyWriter.Context ctx = new ReplyWriter.Context(23L, "Bei ngapi hii", "Jane", "sw", "PRICE", voice, List.of());

    {
        when(claude.isConfigured()).thenReturn(true);
    }

    static ReplyWriter.Written written(String pub, String dm) {
        return new ReplyWriter.Written(pub, dm, "price");
    }

    // ───────────── the hard rules ─────────────

    @Test
    void aCleanRewritePassesTheRules() {
        String dm = "Hey Jane! Hii maxi ni 1500 tu 😍 Iko black na red, tuko Moi Avenue.\n\nUnapenda black ama red?";
        assertEquals(List.of(), ReplyWriter.problems(plan, "Iko dear! Check DM 💚", dm, ReplyWriter.allowedFacts(plan, voice), voice.noGoPhrases()));
    }

    @Test
    void aPriceOrNumberNotInTheFactsIsCaught() {
        String dm = "Hii ni 1,200 tu leo!\n\nUnapenda rangi gani?";
        List<String> p = ReplyWriter.problems(plan, null, dm, ReplyWriter.allowedFacts(plan, voice), null);
        assertTrue(p.stream().anyMatch(x -> x.contains("1200")), p.toString());
    }

    @Test
    void theSellersOwnAnswersCountAsFacts() {
        String dm = "Tunadeliver Kisumu kwa 300 by G4S.\n\nUko wapi Kisumu?";
        assertEquals(List.of(), ReplyWriter.problems(plan, null, dm, ReplyWriter.allowedFacts(plan, voice), null));
    }

    @Test
    void madeUpLinksPhonesInPublicNoGoPhrasesAndBotTalkAreCaught() {
        String allowed = ReplyWriter.allowedFacts(plan, voice);
        assertFalse(ReplyWriter.problems(plan, null, "Order: https://bit.ly/x\n\nRangi gani?", allowed, null).isEmpty());
        assertFalse(ReplyWriter.problems(plan, "Call 0712345678 dear", null, allowed, null).isEmpty());
        assertFalse(ReplyWriter.problems(plan, null, "Free delivery leo!\n\nRangi gani?", allowed, voice.noGoPhrases()).isEmpty());
        assertFalse(ReplyWriter.problems(plan, null, "I'm the shop's bot 😊\n\nRangi gani?", allowed, null).isEmpty());
    }

    @Test
    void thePrivateReplyMustEndWithAQuestion() {
        List<String> p = ReplyWriter.problems(plan, null, "Hii ni 1,500. Karibu!", ReplyWriter.allowedFacts(plan, voice), null);
        assertTrue(p.contains("private reply doesn't end with a question"), p.toString());
    }

    @Test
    void aPublicReplyCannotPromiseADmTheDraftDidnt() {
        Plan noDm = new Plan("Asante sana 💚", null, false, false, null);
        assertFalse(ReplyWriter.problems(noDm, "Asante! Check inbox", null, ReplyWriter.allowedFacts(noDm, voice), null).isEmpty());
    }

    // ───────────── writing ─────────────

    @Test
    void aGoodRewriteIsUsed() {
        String dm = "Hey Jane! Maxi ni 1,500 😍 Iko black na red.\n\nUnapenda black ama red?";
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Written.class), anyString())).thenReturn(written("Iko dear! Check DM 💚", dm));
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Review.class), anyString())).thenReturn(new ReplyWriter.Review(true, null));

        ReplyWriter.Result r = writer.write(plan, ctx);

        assertEquals(dm, r.privateReply());
        assertEquals("Iko dear! Check DM 💚", r.publicReply());
        assertEquals("PASS", r.verdict());
        assertEquals("price", r.angle());
        assertNotEquals("planner", r.model());
    }

    @Test
    void aRewriteThatBreaksARuleFallsBackToThePlanner() {
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Written.class), anyString()))
                .thenReturn(written("Iko!", "Leo ni 999 tu!\n\nRangi gani?"));

        ReplyWriter.Result r = writer.write(plan, ctx);

        assertEquals(plan.privateReply(), r.privateReply());
        assertEquals("planner", r.model());
        assertTrue(r.verdict().startsWith("rules:"), r.verdict());
        verify(claude, never()).readText(anyString(), anyString(), eq(ReplyWriter.Review.class), anyString());
    }

    @Test
    void theCheckerCanStopIt() {
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Written.class), anyString()))
                .thenReturn(written("Iko! DM 💚", "Maxi ni 1,500.\n\nRangi gani?"));
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Review.class), anyString()))
                .thenReturn(new ReplyWriter.Review(false, "doesn't answer the price"));

        ReplyWriter.Result r = writer.write(plan, ctx);

        assertEquals("planner", r.model());
        assertEquals("checker: doesn't answer the price", r.verdict());
        assertEquals(plan.publicReply(), r.publicReply());
    }

    @Test
    void anErrorNeverLosesTheReply() {
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Written.class), anyString())).thenThrow(new RuntimeException("overloaded"));

        ReplyWriter.Result r = writer.write(plan, ctx);

        assertEquals(plan.publicReply(), r.publicReply());
        assertEquals(plan.privateReply(), r.privateReply());
    }

    @Test
    void aPartTheDraftDoesntHaveStaysEmpty() {
        Plan publicOnly = new Plan("Asante sana 💚", null, false, false, null);
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Written.class), anyString()))
                .thenReturn(written("Thank you dear 😍", "Hi!\n\nAnything else?"));
        when(claude.readText(anyString(), anyString(), eq(ReplyWriter.Review.class), anyString())).thenReturn(new ReplyWriter.Review(true, null));

        ReplyWriter.Result r = writer.write(publicOnly, ctx);

        assertEquals("Thank you dear 😍", r.publicReply());
        assertNull(r.privateReply(), "Meta's one private reply is not spent on praise");
    }

    @Test
    void nothingToSayCostsNothing() {
        writer.write(new Plan(null, null, true, false, null), ctx);
        verifyNoInteractions(claude);
    }

    @Test
    void theSellersVoiceAndMemoryReachTheWriter() {
        String system = ReplyWriter.writerSystem(voice);
        assertTrue(system.contains("Clad Hub"));
        assertTrue(system.contains("tuma location tukuletee"), "voice samples");
        assertTrue(system.contains("free delivery"), "no-go list");
        assertTrue(system.contains("KES 300 by G4S"), "the seller's own answers");
    }

    // ───────────── the planner's own wording always ends with a question ─────────────

    static final CommentReplyPlanner.Shop SHOP = new CommentReplyPlanner.Shop("Clad Hub", null, null, "https://wa.me/254700000000");
    static final List<CommentReplyPlanner.Item> DRESS = List.of(
            new CommentReplyPlanner.Item("D1", "Maxi dress", "black", "M", new BigDecimal("1500"), 3));

    @Test
    void everyPrivateReplyFromThePlannerEndsWithAQuestion() {
        for (Intent intent : Intent.values()) {
            for (String lang : List.of("sw", "en")) {
                for (boolean negative : List.of(false, true)) {
                    Reading r = new Reading(intent, false, false, null, null, null, lang, "high", negative);
                    for (List<CommentReplyPlanner.Item> items : List.of(List.<CommentReplyPlanner.Item>of(), DRESS)) {
                        Plan p = CommentReplyPlanner.plan("comment", r, items, SHOP, Duration.ofMinutes(1));
                        if (p.privateReply() != null) {
                            assertTrue(CommentReplyPlanner.endsWithQuestion(p.privateReply()),
                                    intent + "/" + lang + "/negative=" + negative + ": " + p.privateReply());
                        }
                    }
                }
            }
        }
    }
}
