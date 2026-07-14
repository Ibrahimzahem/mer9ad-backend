package com.hackathon.ra9edhamad.domain;

/**
 * Untrusted behavioral signals from the client payload. Kept in a separate family from the
 * coercion signals and from the server-verified facts — they are never mixed.
 */
public record BehavioralSignals(
        double cadenceDeviation,
        int cadenceSamples,
        double deviceInconsistency,
        String baselineSource
) {
    /** Aggregate behavioral suspicion in 0..1. Tunable. */
    public double score() {
        double s = 0.6 * cadenceDeviation + 0.4 * deviceInconsistency;
        return Math.max(0.0, Math.min(1.0, s));
    }
}
