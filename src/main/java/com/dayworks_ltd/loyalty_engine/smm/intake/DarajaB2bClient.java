package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daraja B2B "Business Buy Goods": Tribe's own shortcode pays a few shillings into a seller's till. Safaricom's result
 * (sent to {@code /api/v1/smm/till-check/result/{token}}) carries the M-Pesa code and the till's public name, which is
 * what we put on the seller's videos.
 * <p>
 * Off until {@code mpesa.b2b.initiator} and {@code mpesa.b2b.security-credential} are set (the API operator created on
 * the M-Pesa org portal, and its password encrypted with Safaricom's certificate). Uses the same Daraja app
 * ({@code mpesa.consumerkey/consumersecret}) as STK Push once Business Buy Goods is added to it.
 */
@Slf4j
@Component
public class DarajaB2bClient {

    private final String consumerKey;
    private final String consumerSecret;
    private final String authUrl;
    private final String shortcode;
    private final String initiator;
    private final String securityCredential;
    private final String receiverType;
    private final String paymentUrl;
    private final String callbackBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();

    public DarajaB2bClient(@Value("${mpesa.consumerkey:}") String consumerKey,
                           @Value("${mpesa.consumersecret:}") String consumerSecret,
                           @Value("${mpesa.auth-url:}") String authUrl,
                           @Value("${mpesa.b2b.shortcode:${mpesa.shortcode:}}") String shortcode,
                           @Value("${mpesa.b2b.initiator:}") String initiator,
                           @Value("${mpesa.b2b.security-credential:}") String securityCredential,
                           @Value("${mpesa.b2b.receiver-type:4}") String receiverType,
                           @Value("${mpesa.b2b.url:}") String paymentUrl,
                           @Value("${smm.till-check.callback-base:}") String callbackBase,
                           @Value("${mpesa.callback-url:}") String mpesaCallbackUrl) {
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.authUrl = authUrl;
        this.shortcode = shortcode;
        this.initiator = initiator;
        this.securityCredential = securityCredential;
        this.receiverType = receiverType;
        this.paymentUrl = !paymentUrl.isBlank() ? paymentUrl : origin(authUrl) + "/mpesa/b2b/v1/paymentrequest";
        this.callbackBase = !callbackBase.isBlank() ? callbackBase : origin(mpesaCallbackUrl) + "/api/v1/smm/till-check";
    }

    /** True when the B2B operator is configured; otherwise our team pays by hand and records it. */
    public boolean enabled() {
        return !initiator.isBlank() && !securityCredential.isBlank() && !shortcode.isBlank()
                && !consumerKey.isBlank() && !authUrl.isBlank() && !callbackBase.startsWith("/");
    }

    /**
     * Ask M-Pesa to pay {@code amount} into {@code till}. Returns the ConversationID that the result will carry, or
     * null when Daraja refused the request. The money moves later; the result arrives on the callback.
     */
    public String pay(String till, int amount, String token) {
        try {
            String accessToken = token();
            if (accessToken == null) return null;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Initiator", initiator);
            body.put("SecurityCredential", securityCredential);
            body.put("CommandID", "BusinessBuyGoods");
            body.put("SenderIdentifierType", "4");
            body.put("RecieverIdentifierType", receiverType);
            body.put("Amount", String.valueOf(amount));
            body.put("PartyA", shortcode);
            body.put("PartyB", till);
            body.put("AccountReference", "TRIBE");
            body.put("Remarks", "Tribe till check");
            body.put("QueueTimeOutURL", callbackBase + "/timeout/" + token);
            body.put("ResultURL", callbackBase + "/result/" + token);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(paymentUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            JsonNode res = json.readTree(r.body());
            if (r.statusCode() / 100 == 2 && "0".equals(res.path("ResponseCode").asText())) {
                return res.path("ConversationID").asText(null);
            }
            log.warn("SokoReply: Daraja B2B refused the till check to {} (HTTP {}): {} {}", till, r.statusCode(),
                    res.path("errorCode").asText(res.path("ResponseCode").asText("")),
                    res.path("errorMessage").asText(res.path("ResponseDescription").asText("")));
            return null;
        } catch (Exception e) {
            log.warn("SokoReply: Daraja B2B call for till {} failed: {}", till, e.getMessage());
            return null;
        }
    }

    private String token() throws Exception {
        String basic = Base64.getEncoder().encodeToString((consumerKey + ":" + consumerSecret).getBytes(StandardCharsets.UTF_8));
        String url = authUrl.contains("grant_type=") ? authUrl
                : authUrl + (authUrl.contains("?") ? "&" : "?") + "grant_type=client_credentials";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Basic " + basic).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            log.warn("SokoReply: Daraja token request failed (HTTP {})", r.statusCode());
            return null;
        }
        return json.readTree(r.body()).path("access_token").asText(null);
    }

    static String origin(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI u = URI.create(url.trim());
            return u.getScheme() + "://" + u.getRawAuthority();
        } catch (Exception e) {
            return "";
        }
    }
}
