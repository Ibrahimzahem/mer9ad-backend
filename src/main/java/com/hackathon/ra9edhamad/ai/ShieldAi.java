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
     * Same as {@link #judge(GroundedContext)}, but with findings handed over from an upstream
     * reasoning/investigation step (e.g. the Groq ReAct agent). Implementations that don't use
     * a second-stage handoff can ignore {@code reasoningNotes}; the default just delegates.
     */
    default FraudVerdict judge(GroundedContext context, String reasoningNotes) {
        return judge(context);
    }

    /**
     * Produce the next dialogue turn. {@code latestAnswer} is null for the opening question.
     */
    InterventionTurn intervene(InterventionSession session, String latestAnswer);
}
