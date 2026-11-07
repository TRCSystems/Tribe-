package com.dayworks_ltd.loyalty_engine.smm.repository;

import com.dayworks_ltd.loyalty_engine.smm.model.SmmAccount;
import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmmAccountRepository extends JpaRepository<SmmAccount, Long> {
    /** The Page a webhook is for. */
    Optional<SmmAccount> findByPlatformAndPageId(SocialPlatform platform, String pageId);

    List<SmmAccount> findByMerchantId(Long merchantId);

    /** Accounts whose token the daily check should look at. */
    List<SmmAccount> findByStatus(SmmAccount.Status status);
}
