package com.dayworks_ltd.loyalty_engine.smm.meta;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import com.dayworks_ltd.loyalty_engine.smm.model.TokenCipher;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmAccountRepository;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Connecting a seller's Facebook Pages and Instagram accounts: the login link, the callback (token exchange, storing
 * the Page tokens encrypted, turning on comment webhooks), disconnecting, and the daily token check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmConnectService {

    private final SmmProperties props;
    private final MetaGraphClient graph;
    private final SmmAccountRepository accounts;
    private final SmmSellerProfileRepository profiles;

    public record Connected(long merchantId, List<String> names, List<String> skipped) {}

    public String loginUrl(long merchantId, long userId) {
        List<String> missing = props.missingForConnect();
        if (!missing.isEmpty() || !TokenCipher.isConfigured()) {
            throw new IllegalStateException("Connecting Pages is not set up yet (missing: " + String.join(", ", missing) + ")");
        }
        return graph.loginDialogUrl(ConnectState.create(merchantId, userId, props.getStateSecret(), Instant.now()));
    }

    /** Facebook sent the seller back with a code: store every Page (and linked Instagram) they granted. */
    @Transactional
    public Connected complete(String code, String state) {
        ConnectState.Parsed who = ConnectState.verify(state, props.getStateSecret(), Instant.now());
        String userToken = graph.longLivedUserToken(code);
        List<String> names = new ArrayList<>(), skipped = new ArrayList<>();
        for (MetaGraphClient.ManagedPage page : graph.managedPages(userToken)) {
            if (!save(who, SocialPlatform.FACEBOOK, page.id(), page.name(), page.accessToken(), skipped)) continue;
            names.add(page.name() + " (Facebook)");
            try {
                graph.subscribePage(page.id(), page.accessToken());
            } catch (Exception e) {
                log.warn("Could not turn on comment webhooks for Page {}: {}", page.id(), e.getMessage());
            }
            if (page.instagramId() != null
                    && save(who, SocialPlatform.INSTAGRAM, page.instagramId(), "@" + page.instagramUsername(), page.accessToken(), skipped)) {
                names.add("@" + page.instagramUsername() + " (Instagram)");
            }
        }
        // Every new seller starts in shadow mode: protective actions run, replies are only logged
        profiles.findByMerchantId(who.merchantId()).orElseGet(() ->
                profiles.save(SmmSellerProfile.builder().merchantId(who.merchantId()).build()));
        log.info("Merchant {} connected {} account(s); skipped {}", who.merchantId(), names.size(), skipped.size());
        return new Connected(who.merchantId(), names, skipped);
    }

    /** Saves or refreshes one account; a Page already connected to another shop is never taken over. */
    private boolean save(ConnectState.Parsed who, SocialPlatform platform, String pageId, String name, String token, List<String> skipped) {
        Optional<SmmAccount> existing = accounts.findByPlatformAndPageId(platform, pageId);
        if (existing.isPresent() && !existing.get().getMerchantId().equals(who.merchantId())
                && existing.get().getStatus() != SmmAccount.Status.DISCONNECTED) {
            skipped.add(name + " is connected to another shop");
            return false;
        }
        SmmAccount a = existing.orElseGet(() -> SmmAccount.builder().platform(platform).pageId(pageId).build());
        a.setMerchantId(who.merchantId());
        a.setPageName(name);
        a.setAccessToken(token);
        a.setConnectedByUserId(who.userId());
        a.setStatus(SmmAccount.Status.ACTIVE);
        a.setTokenCheckedAt(LocalDateTime.now());
        accounts.save(a);
        return true;
    }

    public List<SmmAccount> list(long merchantId) {
        return accounts.findByMerchantId(merchantId);
    }

    @Transactional
    public void disconnect(long merchantId, long accountId) {
        SmmAccount a = accounts.findById(accountId).filter(x -> x.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (a.getPlatform() == SocialPlatform.FACEBOOK && a.getStatus() == SmmAccount.Status.ACTIVE) {
            try {
                graph.unsubscribePage(a.getPageId(), a.getAccessToken());
            } catch (Exception e) {
                log.warn("Could not turn off webhooks for Page {}: {}", a.getPageId(), e.getMessage());
            }
        }
        a.setAccessToken(null);
        a.setStatus(SmmAccount.Status.DISCONNECTED);
        accounts.save(a);
    }

    /** Once a day: mark accounts whose token stopped working (password changed, Tribe removed) so the seller reconnects. */
    @Scheduled(cron = "${smm.token-check-cron:0 30 6 * * *}", zone = "Africa/Nairobi")
    public void checkTokens() {
        if (!TokenCipher.isConfigured() || props.getAppId().isBlank()) return;
        for (SmmAccount a : accounts.findByStatus(SmmAccount.Status.ACTIVE)) {
            try {
                MetaGraphClient.TokenInfo info = graph.checkToken(a.getAccessToken());
                a.setTokenCheckedAt(LocalDateTime.now());
                if (info.expiresAt() != null) {
                    a.setTokenExpiresAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(info.expiresAt()), ZoneId.of("Africa/Nairobi")));
                }
                if (!info.valid()) {
                    a.setStatus(SmmAccount.Status.TOKEN_EXPIRED);
                    log.warn("Page token no longer works for merchant {} ({}): seller must reconnect", a.getMerchantId(), a.getPageName());
                }
                accounts.save(a);
            } catch (Exception e) {
                log.warn("Token check failed for account {}: {}", a.getId(), e.getMessage());
            }
        }
    }
}
