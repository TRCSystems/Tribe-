package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Names the place at a shared WhatsApp location pin. A "current location" pin carries only coordinates,
 * so the store would see nothing but a map link; this asks OpenStreetMap (Nominatim, free, no key) for the
 * nearest address. Low volume (one call per order), well inside Nominatim's 1 request/second policy.
 * Returns null when the lookup is off, slow or fails: the order then keeps just the map link.
 */
@Slf4j
@Component
public class PlaceNamer {

    @Value("${whatsapp.shop.place-lookup:true}")
    private boolean enabled = true;

    @Value("${whatsapp.shop.place-lookup-url:https://nominatim.openstreetmap.org/reverse}")
    private String url = "https://nominatim.openstreetmap.org/reverse";

    private final RestTemplate http;

    public PlaceNamer() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4_000);
        factory.setReadTimeout(6_000);
        this.http = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    public String name(double latitude, double longitude) {
        if (!enabled) return null;
        HttpHeaders headers = new HttpHeaders();
        // Nominatim requires an identifying User-Agent
        headers.set("User-Agent", "Tribe-ASANO-shop/1.0 (+https://asano.tribessystems.co.ke)");
        headers.set("Accept-Language", "en");
        String query = url + "?format=jsonv2&zoom=18&addressdetails=1&lat=" + latitude + "&lon=" + longitude;
        try {
            ResponseEntity<Map> res = http.exchange(query, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = res.getBody();
            return body == null ? null : shortName(body);
        } catch (Exception e) {
            log.warn("Place lookup for {},{} failed: {}", latitude, longitude, e.getMessage());
            return null;
        }
    }

    /**
     * "Milimani Road, Milimani, Kakamega" from Nominatim's address parts — the named place first when there is
     * one (a school, a mall), without postcode, county or country.
     */
    @SuppressWarnings("unchecked")
    static String shortName(Map<String, Object> body) {
        List<String> parts = new ArrayList<>();
        Object named = body.get("name");
        if (named != null && !named.toString().isBlank()) parts.add(named.toString().trim());
        if (body.get("address") instanceof Map<?, ?> a) {
            Map<String, Object> addr = (Map<String, Object>) a;
            for (String key : List.of("road", "neighbourhood", "suburb", "village", "town", "city", "county", "state")) {
                if (parts.size() >= 4) break;
                Object v = addr.get(key);
                if (v == null || v.toString().isBlank()) continue;
                String s = v.toString().trim();
                if (parts.stream().noneMatch(p -> p.equalsIgnoreCase(s))) parts.add(s);
            }
        }
        if (parts.isEmpty() && body.get("display_name") != null) {
            // Fall back to the first few parts of the full name, without postcodes or the country (always last)
            String[] all = body.get("display_name").toString().split(",");
            for (int i = 0; i < all.length - 1; i++) {
                String p = all[i].trim();
                if (!p.isEmpty() && !p.matches("\\d+") && parts.size() < 3) parts.add(p);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }
}
