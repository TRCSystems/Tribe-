package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReplyPlanner.Plan;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Writer and the Checker. The planner decides the move and the facts (prices, stock, location, links) from the
 * catalogue; the Writer rewrites that draft fresh in the seller's own voice, so no two buyers get the same canned line;
 * the Checker makes sure nothing was made up before it goes out.
 *
 * <p>Checking happens twice: hard rules first (every price, number and link must come from the facts, no phone number
 * in public, none of the seller's no-go phrases, the private reply ends with one easy question), then a second model
 * reads it as a buyer would. Anything that fails, and any error, falls back to the planner's own wording, so a buyer
 * is never left without an answer because the Writer had a bad moment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyWriter {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Value("${smm.writer.enabled:true}")
    private boolean enabled = true;
    @Value("${smm.writer.model:claude-sonnet-5-5}")
    private String writerModel = "claude-sonnet-5-5";
    @Value("${smm.checker.model:claude-haiku-5-5}")
    private String checkerModel = "claude-haiku-5-5";
    /** Cost guard: at most this many written replies per seller a day; after that the planner's wording is used. */
    @Value("${smm.writer.daily-limit-per-seller:300}")
    private int dailyLimit = 300;

    private final ShoppingListReader claude;
    private final Map<Long, int[]> usedToday = new ConcurrentHashMap<>();
    private volatile LocalDate today = LocalDate.now(NAIROBI);

    /** What the Writer knows about the seller. faq lines are "question → answer" in the seller's words. */
    public record Voice(String shopName, String tone, String voiceSamples, String noGoPhrases, List<String> faq, String shopFacts) {}

    /** One comment to answer. recentReplies are the latest replies sent for this seller, so the angle varies. */
    public record Context(Long merchantId, String comment, String commenterName, String language, String intent,
                          Voice voice, List<String> recentReplies) {}

    /** The replies to send. model "planner" = the planner's own wording was kept (verdict says why). */
    public record Result(String publicReply, String privateReply, String angle, String model, String verdict) {
        static Result planner(Plan plan, String why) {
            return new Result(plan.publicReply(), plan.privateReply(), null, "planner", why);
        }
    }

    record Written(
            @JsonPropertyDescription("The public reply under the comment, rewritten; null when the draft has none")
            String publicReply,
            @JsonPropertyDescription("The private message, rewritten; null when the draft has none. Its last line is one easy question")
            String privateReply,
            @JsonPropertyDescription("One or two words for the angle taken: price, look, stock, location, delivery, ordering, thanks, care")
            String angle) {}

    record Review(
            @JsonPropertyDescription("True when the replies can be sent as they are")
            boolean ok,
            @JsonPropertyDescription("When not ok: the problem in under 15 words; otherwise null")
            String problem) {}

    public Result write(Plan plan, Context ctx) {
        if (plan.publicReply() == null && plan.privateReply() == null) return Result.planner(plan, null);
        if (!enabled || !claude.isConfigured()) return Result.planner(plan, "writer off");
        if (!takeOne(ctx.merchantId())) return Result.planner(plan, "daily writer limit reached");
        try {
            Written w = claude.readText(writerSystem(ctx.voice()), writerInput(plan, ctx), Written.class, writerModel);
            if (w == null) return Result.planner(plan, "writer gave nothing");
            String pub = plan.publicReply() == null ? null : blankTo(w.publicReply(), plan.publicReply());
            String dm = plan.privateReply() == null ? null : blankTo(w.privateReply(), plan.privateReply());

            List<String> problems = problems(plan, pub, dm, allowedFacts(plan, ctx.voice()), ctx.voice().noGoPhrases());
            if (!problems.isEmpty()) {
                log.info("SokoReply writer: kept the planner's wording for merchant {}: {}", ctx.merchantId(), problems);
                return Result.planner(plan, cut("rules: " + String.join("; ", problems), 200));
            }
            Review review = claude.readText(CHECKER, checkerInput(plan, ctx, pub, dm), Review.class, checkerModel);
            if (review != null && !review.ok()) {
                log.info("SokoReply checker: kept the planner's wording for merchant {}: {}", ctx.merchantId(), review.problem());
                return Result.planner(plan, cut("checker: " + review.problem(), 200));
            }
            return new Result(pub, dm, cut(w.angle(), 40), cut(writerModel, 40), "PASS");
        } catch (Exception e) {
            log.warn("SokoReply writer failed for merchant {}, keeping the planner's wording: {}", ctx.merchantId(), e.getMessage());
            return Result.planner(plan, cut("writer error: " + e.getClass().getSimpleName(), 200));
        }
    }

    // ───────────────────────────── the hard rules ─────────────────────────────

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*\\d|\\d");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]]+");
    private static final Pattern BOT = Pattern.compile("(?i)\\b(bot|robot|chatbot|artificial intelligence)\\b|\\bAI\\b");
    private static final Pattern DM_WORDS = Pattern.compile("(?i)\\b(dm|inbox|ujumbe wa ndani)\\b");

    /**
     * What is wrong with the written replies, by rules that need no model: an empty list means they may go to the
     * Checker. allowed is all the text the facts may come from (the planner's draft, the seller's answers, the shop).
     */
    static List<String> problems(Plan plan, String pub, String dm, String allowed, String noGo) {
        List<String> out = new ArrayList<>();
        Set<String> numbers = numbers(allowed);
        for (String text : new String[]{pub, dm}) {
            if (text == null) continue;
            for (String n : numbers(text)) {
                if (n.length() >= 2 && !numbers.contains(n)) out.add("number " + n + " is not in the facts");
            }
            Matcher u = URL.matcher(text);
            while (u.find()) {
                String link = u.group().replaceAll("[.,!?]+$", "");
                if (!allowed.contains(link)) out.add("link not in the facts");
            }
            if (BOT.matcher(text).find()) out.add("calls itself a bot or AI");
            String lower = text.toLowerCase(Locale.ROOT);
            if (noGo != null) {
                for (String phrase : noGo.split("\\R")) {
                    String p = phrase.trim().toLowerCase(Locale.ROOT);
                    if (!p.isEmpty() && lower.contains(p)) out.add("uses the no-go phrase \"" + phrase.trim() + "\"");
                }
            }
        }
        if (pub != null) {
            if (CommentReplyPlanner.hasPhoneNumber(pub)) out.add("phone number in the public reply");
            if (pub.length() > 350) out.add("public reply too long");
            if (DM_WORDS.matcher(pub).find() && (plan.publicReply() == null || !DM_WORDS.matcher(plan.publicReply()).find())) {
                out.add("public reply mentions a DM the draft didn't");
            }
        }
        if (dm != null) {
            if (!CommentReplyPlanner.endsWithQuestion(dm)) out.add("private reply doesn't end with a question");
            if (dm.length() > 1000) out.add("private reply too long");
        }
        return out;
    }

    /** Numbers in the text, thousands separators removed (1,500 and 1500 are the same price). */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) out.add(m.group().replace(",", "").replaceFirst("^0+(?=\\d)", ""));
        return out;
    }

    static String allowedFacts(Plan plan, Voice v) {
        StringBuilder sb = new StringBuilder();
        if (plan.publicReply() != null) sb.append(plan.publicReply()).append('\n');
        if (plan.privateReply() != null) sb.append(plan.privateReply()).append('\n');
        if (v.shopFacts() != null) sb.append(v.shopFacts()).append('\n');
        if (v.faq() != null) v.faq().forEach(f -> sb.append(f).append('\n'));
        return sb.toString();
    }

    // ───────────────────────────── prompts ─────────────────────────────

    /** Per seller, so it stays the same from comment to comment (the model caches it). */
    static String writerSystem(Voice v) {
        StringBuilder sb = new StringBuilder("""
                You are a sales agent for %s, a Kenyan business selling on Facebook and Instagram. A buyer commented on one of its posts. You get a draft reply with the right facts; rewrite it so it sounds like the seller wrote it just now for this buyer, never like a canned template.

                Rules:
                - Keep every fact in the draft exactly: prices, colours, sizes, stock, location, opening hours, links and numbers. Never add a price, discount, delivery promise, stock claim, number or link that is not in the draft or in the seller's answers below. When unsure, leave it out.
                - Answer what the buyer asked first.
                - Write in the draft's language: Swahili (light Sheng is fine if the buyer writes Sheng) or English.
                - Public reply: one short line, under 25 words, friendly. Never a phone number. If the draft says a DM was sent, say so; never mention a DM when the draft doesn't.
                - Private reply: warm and short, at most 6 short lines, with the draft's facts. Its LAST line is exactly one easy question the buyer can answer in a word or two (which colour, which size, which day, which area). Keep the draft's question or ask a better one about the same thing.
                - Vary the opening and the angle from the recent replies you are shown; never start the same way twice in a row. Angles: price first, how it looks or fits, what's in stock, where to find us, delivery, how to order.
                - Use the seller's tone and the way they address people; copy their style from the samples, not their exact words.
                - At most two emojis per message. Use the buyer's first name at most once, only if it looks like a real name.
                - Never say you are a bot or AI, and never sign with a person's name.
                - If the draft says the owner will answer and the seller's answers below cover the question, you may answer from them.
                - When a draft part is null, return null for it.
                """.formatted(v.shopName() == null ? "the shop" : v.shopName()));
        if (v.tone() != null && !v.tone().isBlank()) sb.append("\nThe seller's tone: ").append(v.tone().trim()).append('\n');
        if (v.noGoPhrases() != null && !v.noGoPhrases().isBlank()) {
            sb.append("\nNever use these words or promises:\n").append(v.noGoPhrases().trim()).append('\n');
        }
        if (v.voiceSamples() != null && !v.voiceSamples().isBlank()) {
            sb.append("\nHow the seller writes (their own past replies):\n").append(v.voiceSamples().trim()).append('\n');
        }
        if (v.faq() != null && !v.faq().isEmpty()) {
            sb.append("\nThe seller's own answers to questions buyers asked before (facts you may use):\n");
            v.faq().forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        if (v.shopFacts() != null && !v.shopFacts().isBlank()) sb.append("\nShop facts: ").append(v.shopFacts()).append('\n');
        return sb.toString();
    }

    static String writerInput(Plan plan, Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Buyer's comment: ").append(ctx.comment()).append('\n');
        if (ctx.commenterName() != null) sb.append("Buyer's name: ").append(ctx.commenterName()).append('\n');
        sb.append("What they want: ").append(ctx.intent()).append('\n');
        sb.append("Language: ").append("sw".equalsIgnoreCase(ctx.language()) ? "Swahili" : "English").append("\n\n");
        sb.append("Draft public reply: ").append(plan.publicReply() == null ? "null" : plan.publicReply()).append('\n');
        sb.append("Draft private reply: ").append(plan.privateReply() == null ? "null" : "\n" + plan.privateReply()).append('\n');
        if (ctx.recentReplies() != null && !ctx.recentReplies().isEmpty()) {
            sb.append("\nRecent replies already sent (start and angle differently):\n");
            ctx.recentReplies().forEach(r -> sb.append("- ").append(r.replace('\n', ' ')).append('\n'));
        }
        return sb.toString();
    }

    static final String CHECKER = """
            You check replies a sales agent wrote for a Kenyan business before they are sent to a buyer on Facebook or Instagram. You get the buyer's comment, the draft with the correct facts, the seller's own answers, and the written replies.

            Answer ok=false only for a real problem:
            - a fact that is in neither the draft nor the seller's answers (a price, discount, stock or delivery promise, a link, a number), or a fact changed;
            - it doesn't answer what the buyer asked when the draft did;
            - rude, pushy, odd or confusing wording, or the wrong language for the buyer;
            - the private reply doesn't end with one easy question;
            - it claims to be a bot, AI or a named person.
            Small style choices are fine. Otherwise ok=true.
            """;

    static String checkerInput(Plan plan, Context ctx, String pub, String dm) {
        StringBuilder sb = new StringBuilder();
        sb.append("Buyer's comment: ").append(ctx.comment()).append("\n\n");
        sb.append("Draft public reply: ").append(Objects.toString(plan.publicReply(), "null")).append('\n');
        sb.append("Draft private reply: ").append(Objects.toString(plan.privateReply(), "null")).append("\n\n");
        if (ctx.voice().faq() != null && !ctx.voice().faq().isEmpty()) {
            sb.append("Seller's answers:\n");
            ctx.voice().faq().forEach(f -> sb.append("- ").append(f).append('\n'));
            sb.append('\n');
        }
        if (ctx.voice().shopFacts() != null) sb.append("Shop facts: ").append(ctx.voice().shopFacts()).append("\n\n");
        sb.append("Written public reply: ").append(Objects.toString(pub, "null")).append('\n');
        sb.append("Written private reply: ").append(Objects.toString(dm, "null")).append('\n');
        return sb.toString();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private boolean takeOne(Long merchantId) {
        LocalDate now = LocalDate.now(NAIROBI);
        if (!now.equals(today)) {
            usedToday.clear();
            today = now;
        }
        int[] n = usedToday.computeIfAbsent(merchantId == null ? -1L : merchantId, k -> new int[1]);
        synchronized (n) {
            if (n[0] >= dailyLimit) return false;
            n[0]++;
            return true;
        }
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim()) ? fallback : s.strip();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
