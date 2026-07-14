package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.ai.InterventionTurn;
import com.hackathon.ra9edhamad.domain.InterventionSession;

/**
 * An agent that can hold a live multi-turn conversation with the customer —
 * the grey-zone intervention. Only the FraudAgent implements this today;
 * AML and Compliance agents are one-shot (assess only).
 */
public interface ConversationalAgent extends Agent {

    /**
     * Produce the next dialogue turn. {@code latestAnswer} is null for the opening question.
     * The agent reads coaching signals (scripted / verbatim / too-fast / contradicting facts)
     * and reflects contradictions back to the victim in plain Arabic.
     */
    InterventionTurn intervene(InterventionSession session, String latestAnswer);
}
