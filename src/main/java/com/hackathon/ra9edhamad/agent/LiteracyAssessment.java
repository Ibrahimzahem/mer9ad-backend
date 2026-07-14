package com.hackathon.ra9edhamad.agent;

import java.util.List;

/**
 * Structured output from the LLM literacy agent.
 */
public record LiteracyAssessment(
        String scamName,
        String lesson,
        List<String> signs,
        String action,
        String reasoning
) {
}
