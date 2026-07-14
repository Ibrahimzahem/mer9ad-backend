package com.hackathon.ra9edhamad.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A labeled record of social engineering caught mid-act — the kind of grey-zone data banks
 * normally never capture (they only learn from completed fraud). Conceptually feeds national /
 * GCC mule intelligence.
 */
public record FraudIntelRecord(
        String eventId,
        String customerRef,          // pseudonymous
        String beneficiaryIban,
        Decision decision,
        String outcome,              // PROCEEDED_SAFE | ABANDONED | BLOCKED | CHALLENGE_FAILED
        Map<String, Object> signalsSnapshot,
        String detectedPattern,      // nullable
        Instant timestamp
) {
}
