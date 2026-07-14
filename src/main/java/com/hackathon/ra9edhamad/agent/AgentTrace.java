package com.hackathon.ra9edhamad.agent;

import java.util.List;

/**
 * The full execution trace of an agent — every step it took from receiving the task
 * to producing its final answer. This is what makes the agent's reasoning visible
 * in the mission-control UI.
 *
 * <p>Example trace:
 * <pre>
 * Step 1: THOUGHT — "I need to check if this IBAN is on the mule watchlist"
 * Step 2: TOOL_CALL — checkMuleWatchlist("SA...3333")
 * Step 3: TOOL_RESULT — "YES — this IBAN is on the mule watchlist"
 * Step 4: TOOL_CALL — getAccountInfo("SA...3333")
 * Step 5: TOOL_RESULT — "Account: سلطان | Age: 10 days | Transit: 0.90"
 * Step 6: FINAL_ANSWER — {"decision":"RED", "fraudLikelihood":1.0, ...}
 * </pre>
 */
public record AgentTrace(
        String agentName,
        List<AgentStep> steps,
        int toolCallsCount,
        long totalDurationMs,
        boolean success
) {
    public static AgentTrace of(String agentName, List<AgentStep> steps, long totalMs, boolean success) {
        int toolCalls = (int) steps.stream().filter(s -> s.type() == AgentStep.Type.TOOL_CALL).count();
        return new AgentTrace(agentName, steps, toolCalls, totalMs, success);
    }

    public boolean isEmpty() {
        return steps == null || steps.isEmpty();
    }
}
