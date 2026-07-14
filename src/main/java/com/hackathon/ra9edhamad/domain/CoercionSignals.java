package com.hackathon.ra9edhamad.domain;

/**
 * Untrusted coercion signals from the client payload — the fingerprints of someone being
 * walked through a transfer (a live call, a pasted IBAN from WhatsApp, a long hesitation, a
 * purpose field filled suspiciously fast right after the IBAN).
 *
 * <p>"Presence" thresholds are documented constants here. None of these can, on their own,
 * justify an irreversible RED — they can only raise suspicion to ORANGE.
 */
public record CoercionSignals(
        String ibanEntryMethod,        // typed | pasted | autofilled
        long maxIdleGapMs,
        boolean activeCallDetected,
        boolean remoteAccessAppDetected,
        String purposeEntryMethod,     // typed | pasted | autofilled
        long purposeFilledAfterIbanMs
) {
    private static final long IDLE_GAP_THRESHOLD_MS = 15_000;
    private static final long PURPOSE_TOO_FAST_MS = 1_500;

    private static boolean notTyped(String method) {
        return method != null && !method.equalsIgnoreCase("typed");
    }

    /** True if any single coercion signal is present (used by deterministic triage). */
    public boolean anyPresent() {
        return notTyped(ibanEntryMethod)
                || activeCallDetected
                || remoteAccessAppDetected
                || notTyped(purposeEntryMethod)
                || maxIdleGapMs > IDLE_GAP_THRESHOLD_MS
                || purposeFilledAfterIbanMs < PURPOSE_TOO_FAST_MS;
    }
}
