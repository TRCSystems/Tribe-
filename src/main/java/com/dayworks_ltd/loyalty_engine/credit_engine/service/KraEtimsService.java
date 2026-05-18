package com.dayworks_ltd.loyalty_engine.credit_engine.service;

import com.dayworks_ltd.loyalty_engine.credit_engine.dto.KraCheckerResponse;
import com.dayworks_ltd.loyalty_engine.credit_engine.dto.KraInvoiceDetails;
import com.dayworks_ltd.loyalty_engine.payments.utils.dto.TokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KraEtimsService {

    private final RestClient restClient;

    @Value("${kra.base-url:https://sbx.kra.go.ke}")
    private String baseUrl;

    @Value("${kra.consumer-key}")
    private String consumerKey;

    @Value("${kra.consumer-secret}")
    private String consumerSecret;

    /**
     * Retrieves access token from KRA
     */
    private String getAccessToken() {
        String url = baseUrl + "/v1/token/generate?grant_type=client_credentials";

        try {
            TokenResponse response = restClient.get()
                    .uri(url)
                    .headers(headers -> headers.setBasicAuth(consumerKey, consumerSecret))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.getAccessToken() == null || response.getAccessToken().trim().isEmpty()) {
                log.error("KRA token response was empty or missing access_token. Response: {}", response);
                throw new RuntimeException("KRA returned invalid token response");
            }

            log.debug("Successfully retrieved KRA access token");
            return response.getAccessToken();

        } catch (Exception e) {
            log.error("Failed to get KRA access token", e);
            throw new KraServiceUnavailableException("KRA token service unavailable", e);
        }
    }
    /**
     * Checks invoice details from KRA
     */
    public Optional<KraInvoiceDetails> checkInvoice(String invoiceNumber, LocalDate invoiceDate) {
        try {
            String token = getAccessToken();

            String url = baseUrl + "/checker/v1/invoice";

            KraCheckerResponse response = restClient.post()
                    .uri(url)
                    .headers(headers -> {
                        headers.setBearerAuth(token);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "invoiceNumber", invoiceNumber,
                            "invoiceDate", invoiceDate.toString()
                    ))
                    .retrieve()
                    .body(KraCheckerResponse.class);

            if (response != null && "OK".equals(response.getStatus()) && response.getResponseCode() == 40000) {
                log.info("KRA Checker SUCCESS for invoice: {}", invoiceNumber);
                return Optional.ofNullable(response.getInvoiceDetails());
            }

            log.warn("KRA returned non-success status for invoice {}: {}", invoiceNumber, response);
            return Optional.empty();

        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            log.info("Invoice not found in KRA: {}", invoiceNumber);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("KRA service unavailable for invoice {}. Will retry later.", invoiceNumber, e);
            return Optional.empty();
        }
    }

    public static class KraServiceUnavailableException extends RuntimeException {
        public KraServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}