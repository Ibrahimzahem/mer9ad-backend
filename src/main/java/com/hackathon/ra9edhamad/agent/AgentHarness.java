package com.hackathon.ra9edhamad.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent harness — a real ReAct (Reason → Act → Observe) loop that runs an agent
 * autonomously until it produces a final answer. Unlike AiServices (which hides the loop),
 * this harness captures every step: every thought, every tool call, every tool result.
 *
 * <p>This is what makes the agent act like Claude Code:
 * <ol>
 *   <li>Send the task + system prompt + tool specs to the LLM</li>
 *   <li>The LLM responds with either a final answer or tool execution requests</li>
 *   <li>If tool calls → execute them, record results, feed back to the LLM</li>
 *   <li>Repeat until the LLM gives a final answer (no more tool calls)</li>
 *   <li>Return the full trace — visible in the mission-control UI</li>
 * </ol>
 *
 * <p>Enforces a max-iterations stop to prevent infinite loops.
 */
public class AgentHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentHarness.class);

    private final ChatModel chatModel;
    private final String systemPrompt;
    private final List<Object> toolObjects;
    private final int maxIterations;

    public AgentHarness(ChatModel chatModel, String systemPrompt, List<Object> toolObjects) {
        this(chatModel, systemPrompt, toolObjects, 10);
    }

    public AgentHarness(ChatModel chatModel, String systemPrompt, List<Object> toolObjects, int maxIterations) {
        this.chatModel = chatModel;
        this.systemPrompt = systemPrompt;
        this.toolObjects = toolObjects;
        this.maxIterations = maxIterations;
    }

    /**
     * Run the agent on a task. Returns the full execution trace including the final answer.
     *
     * @param agentName  the agent's display name (for the trace)
     * @param task       the user message describing what to investigate
     * @return the agent's trace — every step, the final answer text, and timing
     */
    public AgentTrace run(String agentName, String task) {
        long startTime = System.currentTimeMillis();
        List<AgentStep> steps = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(task));

        // Build tool specifications and executors from the tool objects
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (Object toolObj : toolObjects) {
            List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(toolObj);
            toolSpecs.addAll(specs);
            for (ToolSpecification spec : specs) {
                Method method = findToolMethod(toolObj, spec);
                if (method != null) {
                    executors.put(spec.name(), new DefaultToolExecutor(toolObj, method));
                }
            }
        }

        log.info("[Harness:{}] starting ReAct loop with {} tools, max {} iterations",
                agentName, toolSpecs.size(), maxIterations);

        try {
            for (int i = 1; i <= maxIterations; i++) {
                long iterStart = System.currentTimeMillis();

                // Send the conversation to the LLM with tool specs
                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolSpecs)
                        .build();

                ChatResponse response = chatModel.chat(request);
                AiMessage aiMessage = response.aiMessage();
                long iterMs = System.currentTimeMillis() - iterStart;

                // If the LLM wants to call tools
                if (aiMessage.hasToolExecutionRequests()) {
                    // Record the LLM's thinking (if any text was provided alongside tool calls)
                    if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                        steps.add(AgentStep.thought(i, aiMessage.text(), iterMs));
                        log.info("[Harness:{}] step {} THOUGHT: {}", agentName, i,
                                truncate(aiMessage.text(), 100));
                    }

                    // Add the AI message (with tool calls) to the conversation
                    messages.add(aiMessage);

                    // Execute each requested tool
                    for (ToolExecutionRequest toolReq : aiMessage.toolExecutionRequests()) {
                        long toolStart = System.currentTimeMillis();
                        String toolName = toolReq.name();
                        String toolArgs = toolReq.arguments();

                        steps.add(AgentStep.toolCall(i, toolName, toolArgs, 0));
                        log.info("[Harness:{}] step {} TOOL_CALL: {}({})", agentName, i,
                                toolName, truncate(toolArgs, 80));

                        String result;
                        try {
                            ToolExecutor executor = executors.get(toolName);
                            if (executor != null) {
                                result = executor.execute(toolReq, null);
                            } else {
                                result = "ERROR: Tool '" + toolName + "' not found.";
                            }
                        } catch (Exception e) {
                            result = "ERROR: " + e.getMessage();
                            log.warn("[Harness:{}] tool {} failed: {}", agentName, toolName, e.getMessage());
                        }

                        long toolMs = System.currentTimeMillis() - toolStart;
                        steps.add(AgentStep.toolResult(i, toolName, result, toolMs));
                        log.info("[Harness:{}] step {} TOOL_RESULT: {} → {}", agentName, i,
                                toolName, truncate(result, 100));

                        // Feed the tool result back into the conversation
                        messages.add(ToolExecutionResultMessage.from(toolReq, result));
                    }

                    // Continue the loop — the LLM will reason about the tool results
                    continue;
                }

                // No tool calls → this is the final answer
                String finalText = aiMessage.text();
                steps.add(AgentStep.finalAnswer(i, finalText, iterMs));
                log.info("[Harness:{}] DONE after {} iterations, {} tool calls, {}ms",
                        agentName, i,
                        steps.stream().filter(s -> s.type() == AgentStep.Type.TOOL_CALL).count(),
                        System.currentTimeMillis() - startTime);

                return AgentTrace.of(agentName, steps, System.currentTimeMillis() - startTime, true);
            }

            // Max iterations reached
            log.warn("[Harness:{}] max iterations ({}) reached without final answer", agentName, maxIterations);
            steps.add(AgentStep.thought(maxIterations,
                    "Agent reached max iterations. Using last available information.", 0));
            return AgentTrace.of(agentName, steps, System.currentTimeMillis() - startTime, false);

        } catch (Exception e) {
            log.error("[Harness:{}] error: {}", agentName, e.getMessage(), e);
            steps.add(AgentStep.thought(steps.size() + 1,
                    "Agent error: " + e.getMessage(), 0));
            return AgentTrace.of(agentName, steps, System.currentTimeMillis() - startTime, false);
        }
    }

    /**
     * Get the final answer text from the trace (the last FINAL_ANSWER step).
     */
    public static String extractFinalAnswer(AgentTrace trace) {
        if (trace == null || trace.steps() == null) return null;
        return trace.steps().stream()
                .filter(s -> s.type() == AgentStep.Type.FINAL_ANSWER)
                .reduce((a, b) -> b)  // get the last one
                .map(AgentStep::content)
                .orElse(null);
    }

    private Method findToolMethod(Object toolObj, ToolSpecification spec) {
        for (Method m : toolObj.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                // The tool name is derived from the method name by ToolSpecifications
                // Convention: method name → tool name (camelCase preserved)
                if (m.getName().equals(spec.name()) ||
                    camelToSnake(m.getName()).equals(spec.name())) {
                    return m;
                }
            }
        }
        // Fallback: try all methods with @Tool
        for (Method m : toolObj.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                return m; // DefaultToolExecutor handles name matching
            }
        }
        return null;
    }

    private String camelToSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
