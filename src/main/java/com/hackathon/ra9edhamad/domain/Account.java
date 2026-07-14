package com.hackathon.ra9edhamad.domain;

/**
 * A beneficiary account as the bank knows it (server-verified facts).
 * The client never supplies these values; they come from the seeded store.
 */
public record Account(
        String iban,
        String holderName,        // account holder's name as the bank knows it
        int accountAgeDays,
        double transitVelocity,   // 0..1, how fast money flows out again (mule-like)
        boolean muleWatchlistHit,
        double verifiedBalance    // balance of this account (not used for limits here)
) {
}
