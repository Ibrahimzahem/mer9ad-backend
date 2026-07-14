package com.hackathon.ra9edhamad.agent;

/**
 * The reasoning AML agent's interface. LangChain4j creates a proxy that implements this.
 * The agent autonomously investigates money-laundering patterns using AML-specific tools.
 */
public interface ReasoningAmlAssistant {

    /**
     * Assess a transfer for money-laundering risk.
     * The agent calls tools (detectStructuring, analyzeAccountRisk, checkRoundTripping,
     * runAmlClassifier, etc.) to investigate, then returns a structured assessment.
     */
    AmlAssessment assessLaunderingRisk(String transactionDetails);
}
