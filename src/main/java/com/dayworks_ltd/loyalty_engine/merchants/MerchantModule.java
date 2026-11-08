package com.dayworks_ltd.loyalty_engine.merchants;

import java.util.*;

/**
 * The parts of Tribe a merchant can buy, chosen at onboarding. The portal shows only these, and
 * {@link PlanGuard} refuses the APIs of the others.
 * <p>
 * Stored on the merchant as a comma list ({@code merchants.modules}). Merchants from before module selection
 * have none stored: their {@link MerchantPlan} decides (SOKOREPLY means SokoReply only, otherwise everything).
 */
public enum MerchantModule {
    POS,
    ORDERS,
    INVENTORY,
    CUSTOMERS,
    REPORTS,
    FINANCE,
    SOKOREPLY;

    /** The modules this merchant has, in a stable order. */
    public static Set<MerchantModule> of(Merchant merchant) {
        if (merchant == null) return EnumSet.allOf(MerchantModule.class);
        Set<MerchantModule> stored = parse(merchant.getModules());
        if (!stored.isEmpty()) return stored;
        return MerchantPlan.of(merchant) == MerchantPlan.SOKOREPLY ? EnumSet.of(SOKOREPLY) : EnumSet.allOf(MerchantModule.class);
    }

    /** "POS, sokoreply,unknown" gives {POS, SOKOREPLY}; unknown names are dropped. */
    public static Set<MerchantModule> parse(String commaList) {
        Set<MerchantModule> out = EnumSet.noneOf(MerchantModule.class);
        if (commaList == null) return out;
        for (String part : commaList.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            try {
                out.add(valueOf(p));
            } catch (IllegalArgumentException ignored) {
                // unknown module name
            }
        }
        return out;
    }

    public static Set<MerchantModule> parse(Collection<String> names) {
        return names == null ? EnumSet.noneOf(MerchantModule.class) : parse(String.join(",", names));
    }

    /** Comma list for storage. */
    public static String join(Set<MerchantModule> modules) {
        return modules.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(null);
    }

    /** The plan that matches a module choice (kept in step for older code and tokens). */
    public static MerchantPlan planFor(Set<MerchantModule> modules) {
        return modules.equals(EnumSet.of(SOKOREPLY)) ? MerchantPlan.SOKOREPLY : MerchantPlan.FULL;
    }
}
