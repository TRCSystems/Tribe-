package com.dayworks_ltd.loyalty_engine.smm.comments;

/**
 * The WhatsApp the seller gets when a bad comment (a complaint, a fraud accusation, an insult) is hidden.
 * It is only a notification: it never carries the comment or who wrote it. The seller logs in to the
 * Tribe portal to read the details and act, so nothing sensitive sits in a WhatsApp chat.
 * Several bad comments on the same post within an hour go out as one alert with the count.
 */
public final class SellerAlert {

    private SellerAlert() {}

    /**
     * @param count     bad comments in this alert (1 or more)
     * @param platform  where they were posted, e.g. "Facebook" or "Instagram"
     * @param portalUrl where the seller logs in to see them
     * @param swahili   the seller's chosen language
     */
    public static String badComments(int count, String platform, String portalUrl, boolean swahili) {
        int n = Math.max(1, count);
        String where = platform == null || platform.isBlank() ? (swahili ? "mitandao" : "social media") : platform;
        if (swahili) {
            return "⚠️ Tribe: " + (n == 1 ? "Umepokea maoni mabaya 1" : "Umepokea maoni mabaya " + n) + " kwenye " + where
                    + " yanayohitaji hatua yako. " + (n == 1 ? "Tumeyaficha" : "Tumeyaficha yote") + " yasionekane na umma.\n"
                    + "Tafadhali ingia kwenye Tribe uone maelezo na uchukue hatua haraka: " + portalUrl;
        }
        return "⚠️ Tribe: " + (n == 1 ? "A bad comment" : n + " bad comments") + " needing your intervention "
                + (n == 1 ? "was" : "were") + " received on " + where + ". "
                + (n == 1 ? "It has been hidden" : "They have been hidden") + " from the public.\n"
                + "Please log in to Tribe to see the details and act promptly: " + portalUrl;
    }
}
