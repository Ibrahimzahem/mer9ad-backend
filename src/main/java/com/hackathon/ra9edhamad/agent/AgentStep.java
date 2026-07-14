package com.hackathon.ra9edhamad.agent;

import java.util.List;

/**
 * One step in the agent's reasoning chain. Captured by the AgentHarness so the
 * mission-control UI can show the agent's thinking process — like watching
 * Claude Code plan, search, and reason.
 *
 * <p>Each step is either:
 * <ul>
 *   <li>A <b>tool call</b>: the agent decided to call a tool (e.g. checkMuleWatchlist)</li>
 *   <li>A <b>tool result</b>: the tool returned data</li>
 *   <li>A <b>thought</b>: the agent's reasoning text (from the AI message)</li>
 * </ul>
 */
public record AgentStep(
        int stepNumber,
        Type type,
        String toolName,       // null for thoughts
        String toolArguments,  // JSON string of the arguments, null for thoughts
        String content,        // tool result text, or the AI's reasoning text
        long durationMs
) {
    public enum Type {
        THOUGHT,        // the LLM's reasoning text before/after tool calls
        TOOL_CALL,      // the LLM requested a tool execution
        TOOL_RESULT,    // the result of a tool execution
        FINAL_ANSWER    // the LLM's final response
    }

    public static AgentStep thought(int n, String text, long ms) {
        return new AgentStep(n, Type.THOUGHT, null, null, text, ms);
    }

    public static AgentStep toolCall(int n, String name, String args, long ms) {
        return new AgentStep(n, Type.TOOL_CALL, name, args, null, ms);
    }

    public static AgentStep toolResult(int n, String name, String result, long ms) {
        return new AgentStep(n, Type.TOOL_RESULT, name, null, result, ms);
    }

    public static AgentStep finalAnswer(int n, String text, long ms) {
        return new AgentStep(n, Type.FINAL_ANSWER, null, null, text, ms);
    }
}
