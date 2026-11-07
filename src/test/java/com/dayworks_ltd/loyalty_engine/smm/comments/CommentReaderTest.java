package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Two-step reading: when the cheap model's "not a buyer" is doubtful, the stronger model's answer is used. */
class CommentReaderTest {

    /** Stands in for Claude: answers per model and records which models were asked. */
    static class FakeClaude extends ShoppingListReader {
        final List<String> asked = new ArrayList<>();
        final Function<String, CommentReader.Reading> cheap, strong;

        FakeClaude(Function<String, CommentReader.Reading> cheap, Function<String, CommentReader.Reading> strong) {
            this.cheap = cheap;
            this.strong = strong;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readText(String system, String text, Class<T> format, String modelId) {
            boolean second = modelId != null && !modelId.isBlank();
            asked.add(second ? modelId : "cheap");
            return (T) (second ? strong : cheap).apply(text);
        }
    }

    static Reading r(Intent intent, boolean buyer, String confidence) {
        return new Reading(intent, buyer, false, null, null, null, "sw", confidence);
    }

    static CommentReader reader(FakeClaude claude, String secondModel) {
        CommentReader reader = new CommentReader(claude);
        ReflectionTestUtils.setField(reader, "secondModel", secondModel);
        return reader;
    }

    @Test
    void doubtfulNotABuyerGetsASecondLookAndItsAnswerIsUsed() {
        FakeClaude claude = new FakeClaude(t -> r(Intent.OTHER, false, "high"), t -> r(Intent.LOCATION, true, "high"));
        Reading reading = reader(claude, "claude-sonnet-5-5").read("Free delivery ama", List.of());

        assertEquals(Intent.LOCATION, reading.intent());
        assertEquals(List.of("cheap", "claude-sonnet-5-5"), claude.asked);
    }

    @Test
    void buyersPlainPraiseAndSpamAreNotReRead() {
        for (Reading first : List.of(r(Intent.PRICE, true, "high"), r(Intent.COMPLIMENT, false, "high"), r(Intent.SPAM, false, "high"))) {
            FakeClaude claude = new FakeClaude(t -> first, t -> fail("should not ask the second model"));
            assertSame(first, reader(claude, "claude-sonnet-5-5").read("😍😍", List.of()));
            assertEquals(List.of("cheap"), claude.asked);
        }
    }

    @Test
    void praiseThatAsksAQuestionOrAnUnsureReadingIsReRead() {
        assertTrue(CommentReader.doubtful("COMPLIMENT", false, "high", "So pretty, where are you?"));
        assertTrue(CommentReader.doubtful("COMPLIMENT", false, "high", "Nice iko wapi"));
        assertTrue(CommentReader.doubtful("COMPLIMENT", false, "low", "Saf"));
        assertTrue(CommentReader.doubtful("COMPLAINT", false, "high", "Mnapost hamjibu"));
        assertFalse(CommentReader.doubtful("COMPLIMENT", false, "high", "Beautiful 😍"));
        assertFalse(CommentReader.doubtful("OTHER", true, "high", "Nimeuona"), "buyer flag set: already a buyer");
        assertFalse(CommentReader.doubtful("LOCATION", false, "low", "Kakamega"), "buying intent: already a buyer");
    }

    @Test
    void noSecondModelMeansOneRead() {
        FakeClaude claude = new FakeClaude(t -> r(Intent.OTHER, false, "low"), t -> fail("no second model set"));
        assertEquals(Intent.OTHER, reader(claude, "").read("Kut", List.of()).intent());
        assertEquals(List.of("cheap"), claude.asked);
    }

    @Test
    void aFailedSecondLookKeepsTheFirstReading() {
        Reading first = r(Intent.OTHER, false, "high");
        FakeClaude claude = new FakeClaude(t -> first, t -> { throw new IllegalStateException("overloaded"); });
        assertSame(first, reader(claude, "claude-sonnet-5-5").read("Nita watafuta", List.of()));
    }

    @Test
    void fancyFontIsPlainBeforeTheModelSeesIt() {
        List<String> seen = new ArrayList<>();
        FakeClaude claude = new FakeClaude(t -> { seen.add(t); return r(Intent.PRICE, true, "high"); }, t -> null);
        reader(claude, "").read("𝐇𝐨𝐰 𝐦𝐮𝐜𝐡", List.of());
        assertEquals(List.of("Comment: How much"), seen);
    }
}
