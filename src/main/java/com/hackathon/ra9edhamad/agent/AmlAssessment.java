package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.domain.Decision;

import java.util.List;

/**
 * Structured output of the reasoning AML agent.
 */
public record AmlAssessment(
        Decision decision,
        double launderingScore,
        String assessment,           // Arabic
        String reasoning,            // English — what the agent investigated
        List<String> toolsUsed,
        String launderingPattern     // STRUCTURING | RAPID_TRANSIT | ROUND_TRIPPING | etc.
) {
}
