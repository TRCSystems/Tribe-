package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Turns a WhatsApp voice note into text, which is then read exactly like a typed shopping list.
 * Speaks the OpenAI-compatible /audio/transcriptions API, so the provider is configuration:
 * OpenAI (https://api.openai.com/v1) or Groq (https://api.groq.com/openai/v1) both work.
 */
@Slf4j
@Component
public class VoiceTranscriber {

    @Value("${voice.transcription.api-key:}")
    private String apiKey;

    @Value("${voice.transcription.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${voice.transcription.model:whisper-1}")
    private String model;

    /** Product names passed as a spelling hint (Whisper-style prompts are cut at ~224 tokens). */
    private static final int HINT_CHARS = 600;

    private final RestTemplate http;

    public VoiceTranscriber() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.http = new RestTemplate(factory);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The words spoken, or blank when nothing could be heard. */
    public String transcribe(byte[] audio, String mimeType, List<Inventory> catalogue) {
        return transcribe(audio, mimeType, catalogue, null);
    }

    /** {@code terms}: extra words to spell the way customers say them, e.g. liquor sizes "kota, nusu, mzinga". */
    @SuppressWarnings("unchecked")
    public String transcribe(byte[] audio, String mimeType, List<Inventory> catalogue, String terms) {
        if (!isConfigured()) throw new IllegalStateException("voice.transcription.api-key is not set");
        String filename = "voice-note" + extension(mimeType);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        form.add("model", model);
        form.add("response_format", "json");
        // No language: customers mix Swahili, Sheng and English in one sentence
        String hint = hint(catalogue, terms);
        if (!hint.isBlank()) form.add("prompt", hint);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> res = http.postForObject(baseUrl.replaceAll("/+$", "") + "/audio/transcriptions",
                new HttpEntity<>(form, headers), Map.class);
        String text = res == null || res.get("text") == null ? "" : res.get("text").toString().trim();
        log.info("Voice note transcribed ({} bytes, model {}): {} chars", audio.length, model, text.length());
        return text;
    }

    /** A shopping-list prompt with the store's product names, so brands like "Jogoo" or "Kabras" are spelled right. */
    static String hint(List<Inventory> catalogue) {
        return hint(catalogue, null);
    }

    static String hint(List<Inventory> catalogue, String terms) {
        StringBuilder sb = new StringBuilder("Order in English, Swahili or Sheng, e.g. nipe mbili, tatu, kumi. ");
        if (terms != null && !terms.isBlank()) sb.append(terms.trim()).append(". ");
        sb.append("Products: ");
        Set<String> seen = new LinkedHashSet<>();
        for (Inventory i : catalogue == null ? List.<Inventory>of() : catalogue) {
            if (i.getItemName() != null) seen.add(spokenSizes(i.getItemName().trim()));
        }
        for (String name : seen) {
            if (sb.length() + name.length() + 2 > HINT_CHARS) break;
            sb.append(name).append(", ");
        }
        return sb.toString().replaceAll(", $", ".");
    }

    /** "KIBAO 1/4" → "KIBAO kota": fractions in the hint make Whisper write sizes as numbers ("1.6") instead of words. */
    static String spokenSizes(String name) {
        return name.replaceAll("\\s*\\b1/4\\b", " kota").replaceAll("\\s*\\b1/2\\b", " nusu")
                .replaceAll("\\s*\\b3/4\\b", " mzinga").replaceAll("\\s+", " ").trim();
    }

    private static String extension(String mimeType) {
        String m = String.valueOf(mimeType).toLowerCase(Locale.ROOT);
        if (m.contains("mpeg") || m.contains("mp3")) return ".mp3";
        if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return ".m4a";
        if (m.contains("amr")) return ".amr";
        if (m.contains("wav")) return ".wav";
        return ".ogg"; // WhatsApp voice notes: audio/ogg; codecs=opus
    }
}
