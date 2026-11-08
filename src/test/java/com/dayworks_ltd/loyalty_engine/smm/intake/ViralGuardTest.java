package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** A viral Reel must not swamp the agents or the Claude bill. */
class ViralGuardTest {

    static final Instant T = Instant.parse("2026-11-08T12:00:00Z");

    @Test
    void onePostGetsItsShareThenWaitsWhileOthersGoAhead() {
        ViralGuard g = new ViralGuard(30);
        for (int i = 0; i < 30; i++) assertTrue(g.take("VIRAL", T.plusSeconds(i)));
        assertFalse(g.take("VIRAL", T.plusSeconds(31)), "the 31st in the same minute waits");
        assertTrue(g.take("QUIET", T.plusSeconds(31)), "another post is not held up");
        assertTrue(g.take("VIRAL", T.plusSeconds(61)), "a minute later the viral post moves again");
        assertTrue(g.take(null, T), "a comment without a post is never held");
    }

    @Test
    void sameWordsOnTheSamePostReuseTheReading() {
        ViralGuard g = new ViralGuard(30);
        CommentReader.Result price = new CommentReader.Result(new Reading(Intent.PRICE, true, false, null, null, null, "sw", "high"), false);
        g.remember("P1", "Bei?", price, T);

        assertSame(price, g.reuse("P1", "bei", T.plusSeconds(60)).orElseThrow(), "case and punctuation don't matter");
        assertSame(price, g.reuse("P1", "  BEIII??  ", T.plusSeconds(60)).orElseThrow(), "stretched letters don't matter");
        assertTrue(g.reuse("P2", "bei?", T).isEmpty(), "another post is read on its own");
        assertTrue(g.reuse("P1", "bei ya black?", T).isEmpty(), "different words are read again");
        assertTrue(g.reuse("P1", "bei?", T.plus(ViralGuard.REUSE_FOR).plusSeconds(1)).isEmpty(), "too old");
    }

    @Test
    void samePersonSameWordsIsAnsweredOnce() {
        ViralGuard g = new ViralGuard(30);
        assertFalse(g.repeatFromSamePerson("P1", "U1", "How much?", T));
        assertTrue(g.repeatFromSamePerson("P1", "U1", "how much", T.plusSeconds(20)));
        assertFalse(g.repeatFromSamePerson("P1", "U2", "how much", T.plusSeconds(20)), "a different person is a new buyer");
        assertFalse(g.repeatFromSamePerson("P2", "U1", "how much", T.plusSeconds(20)), "a different post is a new question");
        assertFalse(g.repeatFromSamePerson("P1", null, "how much", T), "unknown person: never treated as a repeat");
    }

    @Test
    void emojiKeepTheirMeaning() {
        assertNotEquals(ViralGuard.key("P", "😍"), ViralGuard.key("P", "😡"));
    }
}
