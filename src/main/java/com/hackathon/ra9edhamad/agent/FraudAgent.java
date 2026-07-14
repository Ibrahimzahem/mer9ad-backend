package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.ai.FraudVerdict;
import com.hackathon.ra9edhamad.ai.InterventionTurn;
import com.hackathon.ra9edhamad.ai.ShieldAi;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The fraud / social-engineering specialist agent. This is the existing Shield core
 * logic — triage + AI fraud judge + the grey-zone intervention conversation —
 * refactored into the agent interface.
 *
 * <p>This is the FALLBACK implementation. When LangChain4j + Ollama are available,
 * {@link ReasoningFraudAgent} is used instead (real ReAct reasoning with tools).
 * This class is kept as the deterministic fallback and is not Spring-managed.
 */
public class FraudAgent implements ConversationalAgent {

    private static final Logger log = LoggerFactory.getLogger(FraudAgent.class);

    // Triage thresholds (same as the existing pipeline)
    private static final int NEW_ACCOUNT_DAYS = 30;
    private static final double TRANSIT_TRIGGER = 0.4;
    private static final double AMOUNT_FRACTION_TRIGGER = 0.5;
    private static final double BEHAVIORAL_TRIGGER = 0.5;

    // Resolve-guardrail thresholds
    private static final double ALLOW_LIKELIHOOD_CEILING = 0.40;
    private static final double BLOCK_LIKELIHOOD_FLOOR = 0.75;

    private final ShieldAi shieldAi;
    private final ScamPlaybookStore playbook;

    public FraudAgent(ShieldAi shieldAi, ScamPlaybookStore playbook) {
        this.shieldAi = shieldAi;
        this.playbook = playbook;
    }

    @Override
    public String name() {
        return "FraudAgent";
    }

    @Override
    public AgentVerdict assess(AgentContext ctx) {
        ServerFacts facts = ctx.serverFacts();

        // 1. Cheap deterministic triage — CLEAR → GREEN, AI never called
        if (!shouldEscalate(ctx)) {
            return AgentVerdict.green("لا توجد إشارات احتيال أو إكراه.", "FRAUD_TRIAGE_CLEAR");
        }

        // 2. Mule hard fact → RED deterministically, before the AI
        if (facts.muleWatchlistHit()) {
            return AgentVerdict.redHard(1.0,
                    "الحساب المستفيد مُدرج في قائمة حسابات تمرير الأموال.",
                    "FRAUD_MULE_HARD_FACT");
        }

        // 3. AI fraud judge over grounded facts
        GroundedContext grounded = new GroundedContext(
                ctx.customerRef(), ctx.transfer(), ctx.behavioral(), ctx.coercion(),
                facts, ctx.knownScamPatterns() != null ? ctx.knownScamPatterns() : List.of()
        );
        FraudVerdict verdict;
        try {
            verdict = shieldAi.judge(grounded);
        } catch (Exception e) {
            log.warn("[FraudAgent] AI judge failed, using safe default: {}", e.getMessage());
            verdict = FraudVerdict.safeDefault();
        }

        double likelihood = verdict.fraudLikelihood();

        // 4. Resolve with guardrails
        if (verdict.recommendation() == FraudVerdict.Recommendation.ALLOW && likelihood < ALLOW_LIKELIHOOD_CEILING) {
            return AgentVerdict.green(verdict.assessment(), "FRAUD_AI_ALLOW");
        }

        if (verdict.recommendation() == FraudVerdict.Recommendation.BLOCK
                && likelihood >= BLOCK_LIKELIHOOD_FLOOR
                && facts.newAndFastMoving()) {
            return AgentVerdict.redHard(likelihood, verdict.assessment(), "FRAUD_AI_BLOCK_NEW_FAST");
        }

        // 5. Everything else → ORANGE (open intervention)
        return AgentVerdict.orange(likelihood, verdict.assessment(), "FRAUD_AI_CHALLENGE");
    }

    @Override
    public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
        return shieldAi.intervene(session, latestAnswer);
    }

    private boolean shouldEscalate(AgentContext ctx) {
        ServerFacts facts = ctx.serverFacts();
        return ctx.coercion().anyPresent()
                || facts.accountAgeDays() <= NEW_ACCOUNT_DAYS
                || facts.transitVelocity() >= TRANSIT_TRIGGER
                || ctx.transfer().amount() > AMOUNT_FRACTION_TRIGGER * facts.verifiedBalance()
                || ctx.behavioral().score() >= BEHAVIORAL_TRIGGER;
    }
}
