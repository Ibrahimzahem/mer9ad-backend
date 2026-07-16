package com.hackathon.ra9edhamad.agent;

/**
 * Flat, JSON-friendly view of one {@link AgentStep} — shared by the live API response
 * ({@code TransferIntentResponse.agents[].trace}) and the persisted fraud-intel history
 * ({@code FraudIntelRecord.trace}), so both serialize to the same documented shape.
 */
public record AgentStepInfo(
        int step,
        String type,
        String toolName,
        String toolArguments,
        String content,
        long durationMs
) {
    public static AgentStepInfo from(AgentStep s) {
        return new AgentStepInfo(s.stepNumber(), s.type().name(), s.toolName(),
                s.toolArguments(), s.content(), s.durationMs());
    }
}
