package com.hackathon.ra9edhamad.ai;

import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;

/**
 * The AI layer. Two responsibilities: judge an escalated transfer, and drive the multi-turn
 * grey-zone intervention. Implemented by {@code OpenRouterShieldAi} (live) and
 * {@code StubShieldAi} (deterministic, offline-safe demo).
 */
public interface ShieldAi {

    /** Judge an escalated transfer over the grounded facts only. */
    FraudVerdict judge(GroundedContext context);

    /**
     * Produce the next dialogue turn. {@code latestAnswer} is null for the opening question.
     */
    InterventionTurn intervene(InterventionSession session, String latestAnswer);
}
