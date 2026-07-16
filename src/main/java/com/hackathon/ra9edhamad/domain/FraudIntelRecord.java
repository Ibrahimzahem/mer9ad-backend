package com.hackathon.ra9edhamad.domain;

import com.hackathon.ra9edhamad.agent.AgentStepInfo;

import java.time.Instant;
import java.util.List;
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
        Instant timestamp,
        List<AgentStepInfo> trace    // flattened reasoning trace, same shape as agents[].trace; nullable
) {
}
