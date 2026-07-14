package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.domain.Decision;

import java.util.List;

/**
 * The structured output of the reasoning fraud agent. The LLM fills this after
 * investigating the transaction with its tools.
 *
 * <p>{@code reasoning} and {@code toolsUsed} are the key fields for the demo —
 * they show the judges WHAT the agent investigated and HOW it reasoned,
 * making the agentic behavior visible.
 */
public record FraudAssessment(
        Decision decision,           // GREEN / ORANGE / RED
        double fraudLikelihood,      // 0..1
        String assessment,           // Arabic, human-readable explanation
        String recommendation,      // ALLOW / CHALLENGE / BLOCK
        String reasoning,            // the agent's reasoning chain (what it investigated and why)
        List<String> toolsUsed       // which tools the agent called during investigation
) {
}
