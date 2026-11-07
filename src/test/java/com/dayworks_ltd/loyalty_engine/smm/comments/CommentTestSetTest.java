package com.dayworks_ltd.loyalty_engine.smm.comments;

import com.dayworks_ltd.loyalty_engine.smm.comments.CommentReader.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the labelled comment test set (src/test/resources/smm/comments/testset.jsonl): real comments from
 * Kenyan sellers' reels, names removed and phone numbers masked (first 3 digits kept, the rest zeros).
 * The set will later be used to score CommentReader; this test only keeps the file itself honest.
 */
class CommentTestSetTest {

    static List<JsonNode> load() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        try (var in = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(CommentTestSetTest.class.getResourceAsStream("/smm/comments/testset.jsonl")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) rows.add(json.readTree(line));
            }
        }
        return rows;
    }

    @Test
    void everyRowHasAKnownIntentAndAUniqueId() throws Exception {
        List<JsonNode> rows = load();
        assertTrue(rows.size() > 200);
        assertEquals(rows.size(), rows.stream().map(r -> r.get("id").asText()).distinct().count());
        for (JsonNode r : rows) {
            assertDoesNotThrow(() -> Intent.valueOf(r.get("intent").asText()), r.get("id").asText());
            assertFalse(r.get("text").asText().isBlank(), r.get("id").asText());
        }
    }

    @Test
    void phoneLabelsAgreeWithThePhoneDetector() throws Exception {
        for (JsonNode r : load()) {
            String text = r.get("text").asText();
            String phone = r.path("phone").isNull() ? null : r.path("phone").asText(null);
            boolean detected = CommentReplyPlanner.hasPhoneNumber(text);
            // 'ke' = a Kenyan mobile the detector must catch; 'foreign' and 'mistyped' are known gaps for now
            if ("ke".equals(phone)) assertTrue(detected, r.get("id").asText() + ": " + text);
            if (phone == null) assertFalse(detected, r.get("id").asText() + ": " + text);
        }
    }

    @Test
    void numbersAreMasked() throws Exception {
        for (JsonNode r : load()) {
            // each phone-like run of digits (spaces and dashes allowed inside) must be masked: 3 digits then zeros
            var runs = java.util.regex.Pattern.compile("\\+?\\d[\\d\\s-]{5,}\\d").matcher(r.get("text").asText());
            while (runs.find()) {
                String digits = runs.group().replaceAll("[^0-9]", "");
                if (digits.length() >= 7) {
                    assertTrue(digits.substring(3).matches("0+"), "unmasked number in " + r.get("id").asText());
                }
            }
        }
    }
}
