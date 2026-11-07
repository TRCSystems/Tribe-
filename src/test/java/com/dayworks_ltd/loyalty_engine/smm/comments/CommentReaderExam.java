package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Reading;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Reader exam: runs every comment in testset.jsonl through a model and scores it against the hand-made answers.
 * It calls a paid or rate-limited API, so it only runs when asked:
 *
 * <pre>
 *   mvn test -Dtest=CommentReaderExam -Dexam.models=claude:claude-haiku-5-5,groq:llama-3.3-70b-versatile
 * </pre>
 *
 * Keys come from the environment: ANTHROPIC_API_KEY for claude:..., GROQ_API_KEY for groq:... .
 * Answers are saved to target/reader-exam/answers-&lt;model&gt;.jsonl as they arrive, so a run stopped by a rate limit
 * continues where it left off. The report is written to target/reader-exam/report-&lt;time&gt;.md.
 * Pass marks: buyers caught >= 95%, intent right on clear comments >= 85%, no spam taken for a buyer.
 */
class CommentReaderExam {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path OUT = Path.of("target", "reader-exam");
    static final double PASS_BUYER_RECALL = 0.95, PASS_INTENT = 0.85;

    record Row(int line, JsonNode answer) {
        String id() { return answer.get("id").asText(); }
        String text() { return answer.get("text").asText(); }
        boolean unsure() { return answer.path("unsure").asBoolean(); }
        /** Lines 1-221 and the salon rows (841+) were labelled by a person; the rest by agents. */
        boolean handLabelled() { return line < 221 || line >= 840; }
    }

    record Answer(String intent, boolean buyer, boolean wantsWhatsApp, String language, String error, String confidence, String firstIntent, Boolean secondLook) {
        Answer(String intent, boolean buyer, boolean wantsWhatsApp, String language, String error) {
            this(intent, buyer, wantsWhatsApp, language, error, null, null, null);
        }
    }

    @Test
    void exam() throws Exception {
        String models = System.getProperty("exam.models");
        assumeTrue(models != null && !models.isBlank(), "Reader exam skipped (set -Dexam.models=...)");
        Files.createDirectories(OUT);
        List<Row> rows = load();
        int limit = Integer.getInteger("exam.limit", rows.size());
        rows = rows.subList(0, Math.min(limit, rows.size()));

        StringBuilder report = new StringBuilder("# Reader exam, " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n\n"
                + rows.size() + " comments from testset.jsonl. Pass marks: buyers caught >= 95%, intent right on clear comments >= 85%, no spam taken for a buyer.\n");
        for (String spec : models.split(",")) {
            String provider = spec.substring(0, spec.indexOf(':')).trim(), model = spec.substring(spec.indexOf(':') + 1).trim();
            Function<String, Answer> reader = switch (provider) {
                case "claude" -> claude(model);
                case "groq" -> groq(model);
                case "two-step" -> twoStep(model.split(">")[0], model.split(">")[1]);
                default -> throw new IllegalArgumentException("Unknown provider " + provider);
            };
            Map<String, Answer> answers = run(rows, model, reader, provider.equals("groq") ? 1 : 6);
            report.append(score(spec, rows, answers));
        }
        Path file = OUT.resolve("report-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(file, report);
        System.out.println(report);
        System.out.println("Report: " + file.toAbsolutePath());
    }

    // ───────────────────────────── running ─────────────────────────────

    static List<Row> load() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (var in = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(CommentReaderExam.class.getResourceAsStream("/smm/comments/testset.jsonl")), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = in.readLine()) != null) if (!line.isBlank()) rows.add(new Row(n++, JSON.readTree(line)));
        }
        return rows;
    }

    /** Asks the model about every row not answered yet; answers are appended to a file as they come in. */
    static Map<String, Answer> run(List<Row> rows, String model, Function<String, Answer> reader, int threads) throws Exception {
        Path saved = OUT.resolve("answers-" + model.replaceAll("[^A-Za-z0-9.-]", "_") + ".jsonl");
        Map<String, Answer> answers = new ConcurrentHashMap<>();
        if (Files.exists(saved)) {
            for (String l : Files.readAllLines(saved)) {
                JsonNode n = JSON.readTree(l);
                if (n.path("error").isNull() || n.path("error").isMissingNode())
                    answers.put(n.get("id").asText(), JSON.treeToValue(n.get("answer"), Answer.class));
            }
        }
        List<Row> todo = rows.stream().filter(r -> !answers.containsKey(r.id())).toList();
        System.out.printf("%s: %d already answered, %d to ask%n", model, rows.size() - todo.size(), todo.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Object lock = new Object();
        List<Future<?>> jobs = new ArrayList<>();
        for (Row r : todo) {
            jobs.add(pool.submit(() -> {
                Answer a = reader.apply(r.text());
                ObjectNode line = JSON.createObjectNode().put("id", r.id());
                line.set("answer", JSON.valueToTree(a));
                if (a.error() != null) line.put("error", a.error()); else answers.put(r.id(), a);
                synchronized (lock) {
                    try { Files.writeString(saved, line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                return null;
            }));
        }
        for (Future<?> j : jobs) j.get();
        pool.shutdown();
        return answers;
    }

    /** Claude through the same code the live Reader uses (CommentReader -> ShoppingListReader). */
    static Function<String, Answer> claude(String model) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "ANTHROPIC_API_KEY not set");
        ShoppingListReader slr = new ShoppingListReader();
        ReflectionTestUtils.setField(slr, "apiKey", key);
        ReflectionTestUtils.setField(slr, "model", model);
        CommentReader reader = new CommentReader(slr);
        return text -> {
            for (int attempt = 1; ; attempt++) {
                try {
                    Reading r = reader.read(text, List.of());
                    return new Answer(String.valueOf(r.intent()), r.buyer(), r.wantsWhatsApp(), r.language(), null, r.confidence(), null, null);
                } catch (Exception e) {
                    if (attempt >= 4) return new Answer(null, false, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
                    sleep(2000L * attempt);
                }
            }
        };
    }


    static final Set<String> BUYING = Set.of("PRICE", "LOCATION", "AVAILABILITY", "VARIANT", "READY_TO_BUY", "BOOKING", "CONTACT");

    /**
     * The two-step reader: the cheap model reads every comment; a stronger model takes a second look only when the
     * cheap one says "not a buyer" in a doubtful way (other, complaint, unsure, or praise that asks a question).
     * The stronger model's answer is the one used. A buyer is never downgraded, so this can only catch more sales.
     */
    static Function<String, Answer> twoStep(String first, String second) {
        Function<String, Answer> cheap = claude(first), strong = claude(second);
        return text -> {
            Answer a = cheap.apply(text);
            if (a.error() != null || !doubtful(a, text)) return new Answer(a.intent(), a.buyer(), a.wantsWhatsApp(), a.language(), a.error(), a.confidence(), a.intent(), false);
            Answer b = strong.apply(text);
            if (b.error() != null) return new Answer(a.intent(), a.buyer(), a.wantsWhatsApp(), a.language(), null, a.confidence(), a.intent(), false);
            return new Answer(b.intent(), b.buyer(), b.wantsWhatsApp(), b.language(), null, b.confidence(), a.intent(), true);
        };
    }

    /** Not a buyer according to the cheap model, but not clearly praise, spam or chat either. */
    static boolean doubtful(Answer a, String text) {
        return CommentReader.doubtful(a.intent(), a.buyer(), a.confidence(), text); // the same rule live Tribe uses
    }

    /**
     * Groq's OpenAI-style chat API with JSON output. Same instructions as the live Reader, plus the answer format
     * written out (Claude gets the format from the API's structured output).
     */
    static Function<String, Answer> groq(String model) {
        String key = System.getenv("GROQ_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "GROQ_API_KEY not set");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        String system = CommentReader.prompt(List.of()) + """

                Answer with one JSON object only, no other text:
                {"intent": one of PRICE, LOCATION, AVAILABILITY, VARIANT, READY_TO_BUY, BOOKING, CONTACT, COMPLIMENT, COMPLAINT, SPAM, OTHER,
                 "buyer": true or false (any interest in buying), "wantsWhatsApp": true or false,
                 "productCode": null, "colour": colour asked for in English lower case or null, "size": size label or null,
                 "language": "sw" or "en", "confidence": "high" or "low", "negative": true or false}""";
        return text -> {
            for (int attempt = 1; ; attempt++) {
                try {
                    ObjectNode body = JSON.createObjectNode().put("model", model).put("temperature", 0);
                    body.putObject("response_format").put("type", "json_object");
                    var msgs = body.putArray("messages");
                    msgs.addObject().put("role", "system").put("content", system);
                    msgs.addObject().put("role", "user").put("content", "Comment: " + text);
                    HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 429) {
                        long wait = res.headers().firstValue("retry-after").map(s -> (long) (Double.parseDouble(s) * 1000)).orElse(15000L);
                        if (wait > 120_000) return new Answer(null, false, false, null, "rate limit: retry after " + wait / 1000 + "s (daily limit?)");
                        sleep(wait + 500);
                        attempt--;
                        continue;
                    }
                    if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(200, res.body().length())));
                    JsonNode out = JSON.readTree(JSON.readTree(res.body()).at("/choices/0/message/content").asText());
                    sleep(Long.getLong("exam.groq-pause-ms", 2100L)); // free tier: about 30 requests a minute
                    return new Answer(out.path("intent").asText("OTHER").toUpperCase(Locale.ROOT), out.path("buyer").asBoolean(),
                            out.path("wantsWhatsApp").asBoolean(), out.path("language").asText(null), null);
                } catch (Exception e) {
                    if (attempt >= 4) return new Answer(null, false, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
                    sleep(3000L * attempt);
                }
            }
        };
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ───────────────────────────── scoring ─────────────────────────────

    static String score(String spec, List<Row> rows, Map<String, Answer> answers) {
        List<Row> done = rows.stream().filter(r -> answers.containsKey(r.id())).toList();
        StringBuilder s = new StringBuilder("\n## " + spec + "\n\n");
        s.append(String.format("Answered %d of %d comments%s.%n%n", done.size(), rows.size(),
                done.size() < rows.size() ? " (the rest failed or hit a rate limit; run again to continue)" : ""));
        if (done.isEmpty()) return s.toString();

        Function<Row, Answer> a = r -> answers.get(r.id());
        List<Row> clear = done.stream().filter(r -> !r.unsure()).toList();
        List<Row> unsure = done.stream().filter(Row::unsure).toList();
        List<Row> hand = clear.stream().filter(Row::handLabelled).toList();
        List<Row> buyers = done.stream().filter(r -> r.answer().get("buyer").asBoolean()).toList();
        List<Row> notBuyers = done.stream().filter(r -> !r.answer().get("buyer").asBoolean()).toList();
        List<Row> swEn = done.stream().filter(r -> Set.of("sw", "en").contains(r.answer().path("language").asText())).toList();

        double intentClear = rate(clear, r -> intentOk(r, a.apply(r)));
        double recall = rate(buyers, r -> isBuyer(a.apply(r)));
        long falseBuyers = notBuyers.stream().filter(r -> isBuyer(a.apply(r))).count();
        long spamAsBuyer = done.stream().filter(r -> r.answer().get("intent").asText().equals("SPAM") && isBuyer(a.apply(r))).count();
        boolean pass = recall >= PASS_BUYER_RECALL && intentClear >= PASS_INTENT && spamAsBuyer == 0;

        s.append("| Measure | Score |\n|---|---|\n");
        s.append(row("**Buyers caught** (as the live planner decides; most important)", recall, buyers.size()));
        s.append(row("**Intent right, clear comments**", intentClear, clear.size()));
        s.append(row("Intent right, clear comments labelled by a person only", rate(hand, r -> intentOk(r, a.apply(r))), hand.size()));
        s.append(row("Intent right, unsure comments", rate(unsure, r -> intentOk(r, a.apply(r))), unsure.size()));
        s.append(row("Buyer yes/no right, all comments", rate(done, r -> a.apply(r).buyer() == r.answer().get("buyer").asBoolean()), done.size()));
        s.append(row("WhatsApp wish right", rate(done, r -> a.apply(r).wantsWhatsApp() == r.answer().get("wantsWhatsApp").asBoolean()), done.size()));
        s.append(row("Language right (sw/en comments)", rate(swEn, r -> r.answer().get("language").asText().equals(a.apply(r).language())), swEn.size()));
        s.append(String.format("| Non-buyers taken for buyers (would get a sales reply) | %d of %d |%n", falseBuyers, notBuyers.size()));
        s.append(String.format("| Spam taken for a buyer | %d |%n%n", spamAsBuyer));
        long second = done.stream().filter(r -> Boolean.TRUE.equals(a.apply(r).secondLook())).count();
        if (second > 0) s.append(String.format("Second look by the stronger model: %d of %d comments (%.0f%%).%n%n", second, done.size(), 100.0 * second / done.size()));
        s.append(pass ? "**PASS**\n\n" : "**FAIL**\n\n");

        // Intent by intent, and the most common mix-ups
        s.append("| Intent (clear comments) | Right | Of |\n|---|---|---|\n");
        Map<String, List<Row>> byIntent = clear.stream().collect(Collectors.groupingBy(r -> r.answer().get("intent").asText(), TreeMap::new, Collectors.toList()));
        byIntent.forEach((k, v) -> s.append(String.format("| %s | %d | %d |%n", k, v.stream().filter(r -> intentOk(r, a.apply(r))).count(), v.size())));
        Map<String, List<Row>> mixups = clear.stream().filter(r -> !intentOk(r, a.apply(r)))
                .collect(Collectors.groupingBy(r -> r.answer().get("intent").asText() + " read as " + a.apply(r).intent()));
        s.append("\nMost common mix-ups (answer key → model), with examples:\n\n");
        mixups.entrySet().stream().sorted((x, y) -> y.getValue().size() - x.getValue().size()).limit(10).forEach(e ->
                s.append("- **").append(e.getKey()).append("** (").append(e.getValue().size()).append("): ")
                        .append(e.getValue().stream().limit(4).map(r -> "\"" + shorten(r.text()) + "\"").collect(Collectors.joining(", "))).append('\n'));
        s.append("\nBuyers missed (each one a lost sale), first 15:\n\n");
        buyers.stream().filter(r -> !isBuyer(a.apply(r))).limit(15).forEach(r ->
                s.append("- \"").append(shorten(r.text())).append("\" (").append(r.answer().get("intent").asText()).append(")\n"));
        return s.toString();
    }

    /** A buyer the way CommentReplyPlanner decides: the buyer flag or any buying intent. */
    static boolean isBuyer(Answer a) {
        return a.buyer() || BUYING.contains(a.intent());
    }

    /** VARIANT and READY_TO_BUY questions often overlap; the exact intent is what counts here. */
    static boolean intentOk(Row r, Answer a) {
        return r.answer().get("intent").asText().equals(a.intent());
    }

    static double rate(List<Row> rows, java.util.function.Predicate<Row> ok) {
        return rows.isEmpty() ? Double.NaN : rows.stream().filter(ok).count() / (double) rows.size();
    }

    static String row(String name, double rate, int of) {
        return String.format("| %s | %s (of %d) |%n", name, Double.isNaN(rate) ? "-" : String.format("%.1f%%", rate * 100), of);
    }

    static String shorten(String t) {
        t = t.replace('"', '\'');
        return t.length() > 70 ? t.substring(0, 70) + "…" : t;
    }

    @SuppressWarnings("unused")
    private static final Set<String> INTENTS = Arrays.stream(Intent.values()).map(Enum::name).collect(Collectors.toSet());
}
