package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.domain.Decision;

/**
 * One specialist agent in the Shield multi-agent financial-crime platform.
 * Each agent is a different lens on the same transaction: fraud, AML, compliance.
 * The {@link MasterAgent} routes the transfer to all relevant specialists and fuses
 * their verdicts under the strict trust guardrails.
 *
 * <p>Agents are deliberately single-purpose. If two agents do the same thing, merge them.
 * Only agents that need natural-language reasoning implement {@link ConversationalAgent}.
 */
public interface Agent {

    /** Stable short name used in logs and the mission-control UI (e.g. "FraudAgent"). */
    String name();

    /**
     * Assess the transfer from this agent's specialist lens.
     * Must be fast (sub-second for deterministic agents; the LLM agent may take 1-3s).
     * Must never throw — on internal error, return a safe CHALLENGE verdict.
     */
    AgentVerdict assess(AgentContext ctx);

    /** The agent's verdict on a transfer. */
    record AgentVerdict(
            Decision decision,       // GREEN / ORANGE / RED
            double score,            // 0..1 risk score from this agent's lens
            String evidence,         // Arabic, human-readable: what this agent found
            String ruleId,           // stable ID of the rule/model that fired (for audit)
            boolean fromHardFact,    // true only if this RED is backed by a server-verified fact
            AgentTrace trace         // the agent's reasoning chain (tool calls, thoughts) — null for deterministic agents
    ) {
        public static AgentVerdict green(String evidence, String ruleId) {
            return new AgentVerdict(Decision.GREEN, 0.0, evidence, ruleId, false, null);
        }

        public static AgentVerdict orange(double score, String evidence, String ruleId) {
            return new AgentVerdict(Decision.ORANGE, score, evidence, ruleId, false, null);
        }

        public static AgentVerdict redHard(double score, String evidence, String ruleId) {
            return new AgentVerdict(Decision.RED, score, evidence, ruleId, true, null);
        }

        public static AgentVerdict redSoft(double score, String evidence, String ruleId) {
            return new AgentVerdict(Decision.RED, score, evidence, ruleId, false, null);
        }

        public static AgentVerdict safeDefault() {
            return new AgentVerdict(Decision.ORANGE, 0.5,
                    "تعذّر التحقق الكامل من هذا العامل؛ يُنصح بالتحقق الإضافي.",
                    "SAFE_DEFAULT", false, null);
        }

        // Versions with trace for reasoning agents
        public static AgentVerdict green(String evidence, String ruleId, AgentTrace trace) {
            return new AgentVerdict(Decision.GREEN, 0.0, evidence, ruleId, false, trace);
        }

        public static AgentVerdict orange(double score, String evidence, String ruleId, AgentTrace trace) {
            return new AgentVerdict(Decision.ORANGE, score, evidence, ruleId, false, trace);
        }

        public static AgentVerdict redHard(double score, String evidence, String ruleId, AgentTrace trace) {
            return new AgentVerdict(Decision.RED, score, evidence, ruleId, true, trace);
        }

        public static AgentVerdict redSoft(double score, String evidence, String ruleId, AgentTrace trace) {
            return new AgentVerdict(Decision.RED, score, evidence, ruleId, false, trace);
        }
    }
}
