package com.dayworks_ltd.loyalty_engine.smm.order;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmOrderLink;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmOrderLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Creates and looks up the public ordering link for a Reel. One link per (merchant, post): every buyer who comments
 * on that post, and every reply the agents send, shares it, so it is created once and reused — not single-use and
 * secret like {@code ShopOrderLink}. The token is stored in the clear: it is handed to every commenter on purpose,
 * so nothing here turns on it being hard to guess.
 */
@Service
@RequiredArgsConstructor
public class SmmOrderLinkService {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TOKEN_LENGTH = 8;

    private final SmmOrderLinkRepository repository;
    private final SecureRandom random = new SecureRandom();

    /** Public address of the SokoReply order page. */
    @Value("${smm.order-link-base:https://tribessystems.co.ke/s/}")
    private String linkBase;

    /** A Reel stays relevant for a long time; the link is reusable so it doesn't need to be short-lived. */
    @Value("${smm.order-link-days:120}")
    private int linkDays;

    public record Link(String url, String token) {}

    /** Returns the existing link for this post, or creates one. */
    @Transactional
    public Link getOrCreate(Long merchantId, String postId) {
        Optional<SmmOrderLink> existing = repository.findByMerchantIdAndPostId(merchantId, postId);
        if (existing.isPresent()) return toLink(existing.get());

        try {
            SmmOrderLink link = repository.save(SmmOrderLink.builder()
                    .token(randomToken())
                    .merchantId(merchantId)
                    .postId(postId)
                    .expiresAt(LocalDateTime.now().plusDays(linkDays))
                    .build());
            return toLink(link);
        } catch (DataIntegrityViolationException raced) {
            // another thread created it between our check and our insert
            return repository.findByMerchantIdAndPostId(merchantId, postId).map(this::toLink).orElseThrow(() -> raced);
        }
    }

    /** The link row when the token is valid and not expired. */
    public Optional<SmmOrderLink> find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9]{4,20}")) return Optional.empty();
        return repository.findByToken(token).filter(l -> l.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Transactional
    public void recordOpen(SmmOrderLink link) {
        link.setOpenCount(link.getOpenCount() + 1);
        repository.save(link);
    }

    @Transactional
    public void recordOrder(SmmOrderLink link) {
        link.setOrderCount(link.getOrderCount() + 1);
        repository.save(link);
    }

    private Link toLink(SmmOrderLink link) {
        return new Link(linkBase + link.getToken(), link.getToken());
    }

    private String randomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
