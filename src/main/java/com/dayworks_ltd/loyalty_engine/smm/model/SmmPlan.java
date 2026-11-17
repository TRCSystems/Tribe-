package com.dayworks_ltd.loyalty_engine.smm.model;

import java.math.BigDecimal;
import java.util.Set;

/**
 * SokoReply's three monthly subscription tiers. Comment-reading and replying (the core product) runs the
 * same for every tier; what differs is how many platforms a seller may connect and which extra features
 * they get access to.
 */
public enum SmmPlan {

    /** One platform of the seller's choice: Facebook or Instagram, never both. */
    STARTER(new BigDecimal("500"), 1, false, false, false),

    /** Facebook, Instagram and TikTok together, plus cross-platform posting and stock-arrival alerts. */
    GROWTH(new BigDecimal("1500"), 3, true, true, false),

    /** Everything in Growth, plus hiding a seller's contact details from scam replies. */
    PRO(new BigDecimal("3000"), 3, true, true, true);

    private final BigDecimal priceKes;
    private final int maxPlatforms;
    private final boolean crossPlatformPosting;
    private final boolean stockArrivalAlerts;
    private final boolean scamContactHiding;

    SmmPlan(BigDecimal priceKes, int maxPlatforms, boolean crossPlatformPosting, boolean stockArrivalAlerts, boolean scamContactHiding) {
        this.priceKes = priceKes;
        this.maxPlatforms = maxPlatforms;
        this.crossPlatformPosting = crossPlatformPosting;
        this.stockArrivalAlerts = stockArrivalAlerts;
        this.scamContactHiding = scamContactHiding;
    }

    public BigDecimal priceKes() { return priceKes; }

    /** How many distinct platforms (Facebook, Instagram, TikTok) this plan may have connected at once. */
    public int maxPlatforms() { return maxPlatforms; }

    public boolean crossPlatformPosting() { return crossPlatformPosting; }

    public boolean stockArrivalAlerts() { return stockArrivalAlerts; }

    public boolean scamContactHiding() { return scamContactHiding; }

    /** Starter is limited to exactly one of these; Growth and Pro allow all three. */
    public static final Set<SocialPlatform> STARTER_CHOICES = Set.of(SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM);
}
