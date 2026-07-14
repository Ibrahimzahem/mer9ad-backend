package com.hackathon.ra9edhamad.domain;

/**
 * Server-verified facts for a single transfer intent. Recomputed server-side from the seeded
 * stores on every request — never trusted from the client payload. Only these facts may be
 * used to justify an irreversible RED.
 */
public record ServerFacts(
        String beneficiaryIban,
        int accountAgeDays,
        double transitVelocity,
        boolean muleWatchlistHit,
        double verifiedBalance,     // the paying customer's balance
        boolean isKnownPayee,       // beneficiary already in the customer's history
        String historySummary
) {
    /**
     * Non-mule server-verified hard fact: a brand-new account that is already moving money out
     * fast. Strong, but not a confirmed mule — it still requires the AI to vote BLOCK before it
     * becomes an irreversible RED.
     */
    public boolean newAndFastMoving() {
        return accountAgeDays <= 2 && transitVelocity >= 0.8;
    }
}
