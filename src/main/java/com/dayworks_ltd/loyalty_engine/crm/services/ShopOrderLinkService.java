package com.dayworks_ltd.loyalty_engine.crm.services;

import com.dayworks_ltd.loyalty_engine.crm.models.ShopOrderLink;
import com.dayworks_ltd.loyalty_engine.crm.repositories.ShopOrderLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/** Creates and looks up one-time shop ordering links. */
@Service
@RequiredArgsConstructor
public class ShopOrderLinkService {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TOKEN_LENGTH = 10;

    private final ShopOrderLinkRepository repository;
    private final SecureRandom random = new SecureRandom();

    /** Public address of the shop order page, e.g. https://tribessystems.co.ke/o/ */
    @Value("${crm.order-link-base:https://tribessystems.co.ke/o/}")
    private String linkBase;

    @Value("${crm.order-link-days:3}")
    private int linkDays;

    /** token is the raw secret (only known here); it opens both the web page and the WhatsApp order form. */
    public record NewLink(ShopOrderLink link, String url, String token) {}

    @Transactional
    public NewLink create(Long distributorId, Long shopId, Long crmMessageId) {
        String token = randomToken();
        ShopOrderLink link = repository.save(ShopOrderLink.builder()
                .tokenHash(hash(token))
                .distributorId(distributorId)
                .shopMerchantId(shopId)
                .crmMessageId(crmMessageId)
                .expiresAt(LocalDateTime.now().plusDays(linkDays))
                .build());
        return new NewLink(link, linkBase + token, token);
    }

    @Transactional
    public void attachMessage(Long linkId, Long crmMessageId) {
        repository.findById(linkId).ifPresent(l -> {
            l.setCrmMessageId(crmMessageId);
            repository.save(l);
        });
    }

    public Optional<ShopOrderLink> find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9]{6,20}")) return Optional.empty();
        return repository.findByTokenHash(hash(token));
    }

    /** Same lookup but locks the row (use inside the order transaction). */
    public Optional<ShopOrderLink> lock(String token) {
        if (token == null || !token.matches("[A-Za-z0-9]{6,20}")) return Optional.empty();
        return repository.lockByTokenHash(hash(token));
    }

    public ShopOrderLink save(ShopOrderLink link) {
        return repository.save(link);
    }

    private String randomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
