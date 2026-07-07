package com.dayworks_ltd.loyalty_engine.credit_engine.service;

import com.dayworks_ltd.loyalty_engine.credit_engine.dto.InvoiceExtractionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceAutomationService {

    @Value("${automation.url}")
    private String automationUrl;


    public InvoiceExtractionResponse submitInvoiceForProcessing(MultipartFile invoiceFile) {
        try {
            log.info("Submitting invoice for processing: {}", invoiceFile.getOriginalFilename());
            System.out.println("================== Submitting Invoice =======================");
            System.out.println("File: " + invoiceFile.getOriginalFilename());
            System.out.println("Size: " + invoiceFile.getSize() + " bytes");

            // Validate file
            if (invoiceFile.isEmpty()) {
                throw new IllegalArgumentException("Invoice file is required");
            }

            String contentType = invoiceFile.getContentType();
            if (contentType == null) {
                throw new IllegalArgumentException("Unable to determine file type");
            }

            // Allow PDF and common image formats
            boolean isSupported = "application/pdf".equals(contentType) ||
                    "image/png".equals(contentType) ||
                    "image/jpeg".equals(contentType) ||
                    "image/jpg".equals(contentType);

            if (!isSupported) {
                throw new IllegalArgumentException("Only PDF, PNG, and JPEG files are supported");
            }

            // Optional: Additional validation by file extension
            String filename = Objects.requireNonNull(invoiceFile.getOriginalFilename()).toLowerCase();
            if (!filename.endsWith(".pdf") &&
                    !filename.endsWith(".png") &&
                    !filename.endsWith(".jpg") &&
                    !filename.endsWith(".jpeg")) {
                throw new IllegalArgumentException("File extension not supported");
            }

            // Create multipart request
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] multipartBody = createMultipartBody(invoiceFile, boundary);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(automationUrl + "/invoices"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Submission response - Status: {}, Body: {}", response.statusCode(), response.body());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                InvoiceExtractionResponse extractionResponse = parseResponse(response.body());

                log.info("Invoice submitted successfully. Submission ID: {}, Status: {}",
                        extractionResponse.getInvoiceSubmissionId(), extractionResponse.getStatus());

                return extractionResponse;  // This will be "pending"
            }

            throw new RuntimeException("Submission failed with HTTP status: " + response.statusCode());

        } catch (Exception e) {
            log.error("Failed to submit invoice for processing", e);
            throw new RuntimeException("Submission failed: " + e.getMessage(), e);
        }
    }

    /**
     * Step 2: Check the processing status and get extracted data
     * Call this repeatedly (poll) until status = "complete"
     */
    public InvoiceExtractionResponse getInvoiceExtractionResult(String invoiceSubmissionId) {
        try {
            log.info("Checking extraction status for submissionId: {}", invoiceSubmissionId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(automationUrl + "/invoices?invoiceSubmissionId=" + invoiceSubmissionId))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Status check response - Status: {}, Body: {}", response.statusCode(), response.body());

            if (response.statusCode() == 200) {
                InvoiceExtractionResponse result = parseResponse(response.body());

                if ("complete".equalsIgnoreCase(result.getStatus())) {
                    log.info("Extraction complete for submissionId: {}", invoiceSubmissionId);
                } else if ("pending".equalsIgnoreCase(result.getStatus())) {
                    log.info("Still pending for submissionId: {}", invoiceSubmissionId);
                } else if ("error".equalsIgnoreCase(result.getStatus())) {
                    log.warn("Extraction error for submissionId: {}, message: {}",
                            invoiceSubmissionId, result.getError());
                }

                return result;
            }

            throw new RuntimeException("Status check failed with HTTP status: " + response.statusCode());

        } catch (Exception e) {
            log.error("Failed to check extraction status for {}", invoiceSubmissionId, e);
            throw new RuntimeException("Status check failed: " + e.getMessage(), e);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Your existing helper methods (unchanged)
    // ────────────────────────────────────────────────────────────────

    private byte[] createMultipartBody(MultipartFile file, String boundary) throws IOException {
        String CRLF = "\r\n";
        StringBuilder bodyBuilder = new StringBuilder();

        bodyBuilder.append("--").append(boundary).append(CRLF);
        bodyBuilder.append("Content-Disposition: form-data; name=\"invoice_file\"; filename=\"")
                .append(file.getOriginalFilename()).append("\"").append(CRLF);
        bodyBuilder.append("Content-Type: ").append(file.getContentType()).append(CRLF);
        bodyBuilder.append(CRLF);

        byte[] headerBytes = bodyBuilder.toString().getBytes();
        byte[] fileBytes = file.getBytes();
        byte[] footerBytes = (CRLF + "--" + boundary + "--" + CRLF).getBytes();

        byte[] result = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileBytes.length, footerBytes.length);

        return result;
    }

    private InvoiceExtractionResponse parseResponse(String jsonResponse) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            return mapper.readValue(jsonResponse, InvoiceExtractionResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse JSON response", e);
            throw new RuntimeException("Failed to parse response: " + e.getMessage(), e);
        }
    }
}