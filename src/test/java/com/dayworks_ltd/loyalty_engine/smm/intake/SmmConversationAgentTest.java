package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import com.dayworks_ltd.loyalty_engine.smm.comments.ReplyWriter;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmmConversationAgentTest {

    static final String LINK = "https://tribessystems.co.ke/s/abc123";

    SmmChatMessageRepository chat = mock(SmmChatMessageRepository.class);
    SmmReplyRepository replies = mock(SmmReplyRepository.class);
    SmmCommentRepository comments = mock(SmmCommentRepository.class);
    SmmAccountRepository accounts = mock(SmmAccountRepository.class);
    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    SmmHandoverRepository handovers = mock(SmmHandoverRepository.class);
    MetaGraphClient graph = mock(MetaGraphClient.class);
    ShoppingListReader claude = mock(ShoppingListReader.class);
    SmmCommentWorker worker = mock(SmmCommentWorker.class);
    SmmLeadRepository leads = mock(SmmLeadRepository.class);
    SmmLeadAlertService leadAlerts = mock(SmmLeadAlertService.class);
    SmmConversationAgent agent = new SmmConversationAgent(chat, replies, comments, accounts, profiles, handovers, graph, claude, worker, leads, leadAlerts);

    SmmSellerProfile profile = SmmSellerProfile.builder().merchantId(23L).shadowMode(false).build();
    SmmChatMessage buyer = SmmChatMessage.builder().id(1L).merchantId(23L).replyId(5L).commentId(9L).buyerId("PSID9")
            .fromBuyer(true).text("I will visit tomorrow").status(SmmChatMessage.Status.RECEIVED)
            .createdAt(LocalDateTime.now(SmmIntakeService.NAIROBI)).build();

    @BeforeEach
    void setUp() {
        when(claude.isConfigured()).thenReturn(true);
        when(replies.findById(5L)).thenReturn(Optional.of(SmmReply.builder().id(5L).merchantId(23L).commentId(9L)
                .kind(SmmReply.Kind.PRIVATE).text("We are on Tom Mboya Street. Which item would you like to see?").build()));
        SmmComment comment = SmmComment.builder().id(9L).merchantId(23L).accountId(1L).postId("POST1").text("Where are you located").language("en").build();
        when(comments.findById(9L)).thenReturn(Optional.of(comment));
        when(profiles.findByMerchantId(23L)).thenReturn(Optional.of(profile));
        when(accounts.findById(1L)).thenReturn(Optional.of(SmmAccount.builder().id(1L).merchantId(23L)
                .status(SmmAccount.Status.ACTIVE).accessToken("TOKEN").build()));
        when(chat.findByReplyIdOrderByIdAsc(5L)).thenReturn(new ArrayList<>(List.of(buyer)));
        when(chat.findTop40ByStatusOrderByIdAsc(SmmChatMessage.Status.RECEIVED)).thenReturn(new ArrayList<>(List.of(buyer)));
        CommentReplyPlanner.Shop shop = new CommentReplyPlanner.Shop("Clad Hub", "Tom Mboya Street", LINK, null, null, "8AM-7PM");
        ReplyWriter.Voice voice = new ReplyWriter.Voice("Clad Hub", null, null, "cheap", List.of(), "Location: Tom Mboya Street. Open: 8AM-7PM.");
        List<CommentReplyPlanner.Item> items = List.of(new CommentReplyPlanner.Item("D1", "Wrap dress", "black", "M", new BigDecimal("2200"), 3));
        when(worker.chatFacts(any(), any(), any())).thenReturn(new SmmCommentWorker.ChatFacts(shop, items, voice));
        when(graph.sendMessage(anyString(), anyString(), anyString())).thenReturn(new MetaGraphClient.SentMessage("PSID9", "mid.out"));
    }

    private void modelSays(String reply, boolean handover, String reason) {
        when(claude.readText(anyString(), anyString(), eq(SmmConversationAgent.Turn.class), anyString()))
                .thenReturn(new SmmConversationAgent.Turn(reply, handover, reason));
    }

    @Test
    void aBuyersAnswerGetsAReplyThatIsSentAndStored() {
        modelSays("Karibu! We are on Tom Mboya Street, open 8AM-7PM. Which item should we keep aside for you?", false, null);
        agent.run();

        verify(graph).sendMessage(eq("PSID9"), contains("keep aside"), eq("TOKEN"));
        ArgumentCaptor<SmmChatMessage> saved = ArgumentCaptor.forClass(SmmChatMessage.class);
        verify(chat).save(saved.capture());
        assertFalse(saved.getValue().isFromBuyer());
        assertEquals(SmmChatMessage.Status.SENT, saved.getValue().getStatus());
        assertEquals(SmmChatMessage.Status.ANSWERED, buyer.getStatus());
        verifyNoInteractions(handovers);
    }

    @Test
    void anInventedPriceOrLinkIsNeverSentTheSellerTakesOver() {
        modelSays("The dress is KES 1800 today, order here: https://example.com/pay", false, null);
        agent.run();

        verify(graph).sendMessage(eq("PSID9"), eq(SmmConversationAgent.handoverLine(false)), eq("TOKEN"));
        verify(handovers).save(argThat(h -> h.getReason() == SmmHandover.Reason.UNSURE && h.getCommentId() == 9L));
    }

    @Test
    void aHandoverSpeaksAsTheShopThenOneHoldingLineThenQuiet() {
        modelSays("Let me check with the owner on that. Which colour do you want?", true, "HAGGLE");
        agent.run();
        // no "owner", no question: the fixed wording goes out instead
        verify(graph).sendMessage(eq("PSID9"), eq(SmmConversationAgent.handoverLine(false)), eq("TOKEN"));
        verify(handovers).save(argThat(h -> h.getReason() == SmmHandover.Reason.HAGGLE));
        verify(leadAlerts).maybeAlert(23L);

        // the buyer asks again: one holding line, and the seller is alerted again
        SmmChatMessage handed = SmmChatMessage.builder().replyId(5L).fromBuyer(false).status(SmmChatMessage.Status.SENT).handover(true).build();
        when(chat.findByReplyIdOrderByIdAsc(5L)).thenReturn(List.of(buyer, handed));
        assertNull(agent.answer(buyer));
        verify(graph).sendMessage(eq("PSID9"), eq(SmmConversationAgent.holdingLine(false)), eq("TOKEN"));
        verify(leadAlerts, times(2)).maybeAlert(23L);

        // and again: nothing more, the same line is not repeated
        SmmChatMessage holding = SmmChatMessage.builder().replyId(5L).fromBuyer(false).status(SmmChatMessage.Status.SENT).note(SmmConversationAgent.HOLDING).build();
        when(chat.findByReplyIdOrderByIdAsc(5L)).thenReturn(List.of(buyer, handed, holding));
        assertEquals("With the seller", agent.answer(buyer));
        verify(graph, times(2)).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void theSellersCaptionIsAFactTheAgentMayQuote() {
        ReplyWriter.Voice voice = new ReplyWriter.Voice("ASANO GROUP", null, null, null, List.of(),
                "Location: Tom Mboya Street. The caption the seller wrote on this post (its prices, sizes and details are facts about the item in the post): \"HP EliteBook 840 G5, 8GB RAM, KES 32,000\"");
        String facts = SmmConversationAgent.factSheet(new CommentReplyPlanner.Shop("ASANO GROUP", "Tom Mboya Street", null, null), List.of(), voice);
        assertTrue(facts.contains("HP EliteBook 840 G5"));
        assertTrue(SmmConversationAgent.problems("The HP EliteBook 840 G5 on the post is KES 32,000. Would you like to pick it up or have it delivered?", facts, "", null).isEmpty());
        assertFalse(SmmConversationAgent.problems("It is KES 30,000 for you", facts, "", null).isEmpty(), "a price not in the caption is still blocked");
    }

    @Test
    void nothingIsSentInShadowModeWhilePausedOrAfter24Hours() {
        modelSays("Karibu!", false, null);
        profile.setShadowMode(true);
        assertEquals("Shadow mode", agent.answer(buyer));
        profile.setShadowMode(false);
        profile.setAgentsPaused(true);
        assertEquals("Agents paused", agent.answer(buyer));
        profile.setAgentsPaused(false);
        buyer.setCreatedAt(LocalDateTime.now(SmmIntakeService.NAIROBI).minusHours(25));
        assertEquals("More than 24 hours after the buyer's message", agent.answer(buyer));
        verifyNoInteractions(graph);
    }

    @Test
    void aBudgetBelowThePriceCanBeAnsweredFromTheSellersOtherPosts() {
        String facts = SmmConversationAgent.factSheet(new CommentReplyPlanner.Shop("ASANO GROUP", "Tom Mboya Street", null, null),
                List.of(new CommentReplyPlanner.Item("D1", "HP OMEN 15", null, null, new BigDecimal("86000"), null)),
                new ReplyWriter.Voice("ASANO GROUP", null, null, null, List.of(),
                        "Other items this seller has posted (from their own captions; each is its own post, not this one):\n- Dell Latitude, KES 29,000\n"));
        assertTrue(facts.contains("Dell Latitude"));
        assertTrue(SmmConversationAgent.problems("The HP OMEN is 86,000, but we have a Dell Latitude at 29,000. Want that one instead?", facts, "", null).isEmpty());
        assertFalse(SmmConversationAgent.problems("We have one for 25,000", facts, "", null).isEmpty(), "a price not actually in the range is still blocked");
    }

    @Test
    void theOrderLinkAndCataloguePricesPassTheCheck() {
        String facts = SmmConversationAgent.factSheet(new CommentReplyPlanner.Shop("Clad Hub", "Tom Mboya Street", LINK, null),
                List.of(new CommentReplyPlanner.Item("D1", "Wrap dress", "black", "M", new BigDecimal("2200"), 3)),
                new ReplyWriter.Voice("Clad Hub", null, null, null, List.of(), null));
        assertTrue(SmmConversationAgent.problems("The black one is KES 2,200. Order here: " + LINK + ". Which size?", facts, "", null).isEmpty());
        assertFalse(SmmConversationAgent.problems("It is 1,900 for you", facts, "", null).isEmpty());
        assertFalse(SmmConversationAgent.problems("I am a bot", facts, "", null).isEmpty());
        assertTrue(SmmConversationAgent.problems("See you at 5!", facts, "Buyer: I will come at 5pm", null).isEmpty(), "a number the buyer said may be repeated");
    }

    @Test
    void theFirstPrivateMessageLosesItsLinksButKeepsItsQuestion() {
        Plan plan = new Plan("We have sent you a DM", "Our shop is on Tom Mboya Street.\nYou can also reach us on WhatsApp: https://wa.me/254700000000\n\nOrder here: " + LINK + "\nWhich item would you like to see?", false, false, null, false);
        Plan out = SmmCommentWorker.withoutDmLinks(plan);
        assertFalse(CommentReplyPlanner.hasLink(out.privateReply()));
        assertTrue(out.privateReply().startsWith("Our shop is on Tom Mboya Street."));
        assertTrue(out.privateReply().endsWith("Which item would you like to see?"));
        assertEquals(LINK, SmmCommentWorker.withoutLinkLines(LINK), "a message that is only a link is kept");
    }

    @Test
    void howMuchAndWhereWithNoProductTaggedStillAnswersWhere() {
        CommentReplyPlanner.Shop shop = new CommentReplyPlanner.Shop("ASANO GROUP", "Tom Mboya Street, Nairobi CBD", null, null, null, "8AM-7PM");
        Plan p = CommentReplyPlanner.plan("How much and where are you located?",
                new com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading(
                        com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent.PRICE, true, false, null, null, null, "en", "high"),
                List.of(), shop, java.time.Duration.ofMinutes(1));
        assertTrue(p.publicReply().contains("Tom Mboya Street"), p.publicReply());
        assertTrue(p.privateReply().contains("Tom Mboya Street") && p.privateReply().contains("8AM-7PM"), p.privateReply());
        assertTrue(p.privateReply().contains("price"), "the price half is not ignored: " + p.privateReply());
        assertTrue(p.handover(), "the seller still hears about the price question");
    }

    @Test
    void theCheckerIsToldTheBuyersRealName() {
        ReplyWriter.Voice voice = new ReplyWriter.Voice("ASANO GROUP", null, null, null, List.of(), null);
        ReplyWriter.Context ctx = new ReplyWriter.Context(23L, "How much?", "Ronald", "en", "PRICE", voice, List.of());
        String shown = ReplyWriter.checkerInput(new Plan("Thanks!", "Which item?", false, false, null, false), ctx, "Thanks Ronald!", "Which item?");
        assertTrue(shown.contains("Buyer's name") && shown.contains("Ronald"));
    }

    @Test
    void thePublicReplySaysWhereToFindTheDm() {
        assertTrue(SmmCommentWorker.withDmHint("We have sent you a DM 💚", "details", "en").endsWith("(Check Message requests.)"));
        assertTrue(SmmCommentWorker.withDmHint("Tumekutumia DM 💚", "details", "sw").endsWith("(Angalia Message requests.)"));
        assertEquals("It is 86,000. We have sent you a DM (Check Message requests.)",
                SmmCommentWorker.plain(SmmCommentWorker.withDmHint("It is 86,000 💚 We have sent you a DM 📍", "details", "en")), "no emojis go out");
        assertEquals("Line one\nWhich colour?", SmmCommentWorker.plain("Line one 💚\nWhich colour? 🙏"));
        assertEquals("Thanks 💚", SmmCommentWorker.withDmHint("Thanks 💚", "details", "en"), "no DM mentioned, no hint");
        assertEquals("We have sent you a DM", SmmCommentWorker.withDmHint("We have sent you a DM", null, "en"), "no DM going out, no hint");
    }
}
