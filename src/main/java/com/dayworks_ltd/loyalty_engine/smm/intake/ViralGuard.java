package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Keeps a viral Reel from swamping the agents. Three rules, all in memory (a restart just starts afresh):
 * <ul>
 *   <li>At most {@code perPostPerMinute} comments of one post are handled per minute; the rest wait their turn while
 *   other posts' comments go ahead.</li>
 *   <li>The same text on the same post within {@link #REUSE_FOR} reuses the first reading instead of paying for another
 *   ("bei?" two hundred times is one model call). The replies are still planned for each commenter.</li>
 *   <li>The same person posting the same text on the same post again is answered once.</li>
 * </ul>
 */
final class ViralGuard {

    static final Duration WINDOW = Duration.ofMinutes(1);
    static final Duration REUSE_FOR = Duration.ofMinutes(30);
    private static final int MAX_REMEMBERED = 5000;

    private final int perPostPerMinute;
    private final Map<String, Deque<Instant>> handled = new HashMap<>();
    private final Map<String, Remembered> readings = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };
    private final Map<String, Instant> seenFromPerson = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    private record Remembered(CommentReader.Result result, Instant at) {}

    ViralGuard(int perPostPerMinute) {
        this.perPostPerMinute = Math.max(1, perPostPerMinute);
    }

    /** True when this post may have one more comment handled now; counts it if so. */
    synchronized boolean take(String postId, Instant now) {
        if (postId == null) return true;
        Deque<Instant> times = handled.computeIfAbsent(postId, k -> new ArrayDeque<>());
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(WINDOW))) times.pollFirst();
        if (times.size() >= perPostPerMinute) return false;
        times.addLast(now);
        return true;
    }

    /** A reading already made for the same text on the same post, if recent. */
    synchronized Optional<CommentReader.Result> reuse(String postId, String text, Instant now) {
        Remembered r = readings.get(key(postId, text));
        if (r == null || r.at().isBefore(now.minus(REUSE_FOR))) return Optional.empty();
        return Optional.of(r.result());
    }

    synchronized void remember(String postId, String text, CommentReader.Result result, Instant now) {
        if (postId != null && result != null && result.reading() != null) readings.put(key(postId, text), new Remembered(result, now));
    }

    /** True the second time the same person posts the same text on the same post (within the reuse window). */
    synchronized boolean repeatFromSamePerson(String postId, String personId, String text, Instant now) {
        if (postId == null || personId == null) return false;
        String k = personId + "|" + key(postId, text);
        Instant before = seenFromPerson.put(k, now);
        return before != null && before.isAfter(now.minus(REUSE_FOR));
    }

    /** Same post, same words: case, spacing, punctuation and repeated letters don't matter ("Bei??" = "bei"). */
    static String key(String postId, String text) {
        String t = text == null ? "" : java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\p{So} ]", " ").replaceAll("(\\p{L})\\1{2,}", "$1").replaceAll("\\s+", " ").trim();
        return postId + "|" + t;
    }
}
