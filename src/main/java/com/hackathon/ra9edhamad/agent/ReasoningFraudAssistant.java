package com.hackathon.ra9edhamad.agent;

/**
 * The reasoning fraud agent's interface. LangChain4j's AiServices creates a proxy
 * that implements this — when you call assessTransaction(), the LLM reasons about
 * what to investigate, calls tools autonomously, and returns a structured assessment.
 *
 * <p>This is a REAL agent: the LLM decides which tools to call (checkMuleWatchlist,
 * getAccountInfo, scoreAmlRisk, etc.), in what order, based on what it finds.
 * That's the ReAct pattern (Reason → Act → Observe → Reason again).
 */
public interface ReasoningFraudAssistant {

    /**
     * Assess a transfer for fraud and money-laundering risk.
     * The agent will autonomously call tools to investigate, then return a verdict.
     *
     * @param transactionDetails a description of the transfer to assess
     * @return a structured fraud assessment with decision, score, reasoning, and tools used
     */
    FraudAssessment assessTransaction(String transactionDetails);
}
