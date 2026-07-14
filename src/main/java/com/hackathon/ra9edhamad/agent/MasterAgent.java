package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.agent.Agent.AgentVerdict;
import com.hackathon.ra9edhamad.ai.InterventionTurn;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import com.hackathon.ra9edhamad.domain.InterventionSessionStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.FraudIntelRecord;
import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The orchestrator. Routes each transfer intent to all specialist agents, fuses their
 * verdicts under the strict trust guardrails, and makes the final decision.
 *
 * <p><b>Fusion rules (the trust principle):</b>
 * <ul>
 *   <li>Any agent RED with a hard fact → final RED (deterministic, before any AI).</li>
 *   <li>Any agent RED without a hard fact → at least ORANGE (client signals can't force RED).</li>
 *   <li>Any agent ORANGE → ORANGE (open the intervention dialogue).</li>
 *   <li>All agents GREEN → GREEN.</li>
 * </ul>
 *
 * <p>On ORANGE, the Master opens the intervention via the {@link ConversationalAgent}
 * (the FraudAgent) and logs every grey-zone outcome to the fraud-intelligence store.
 */
@Service
public class MasterAgent {

    private static final Logger log = LoggerFactory.getLogger(MasterAgent.class);

    private final List<Agent> specialistAgents;
    private final ConversationalAgent fraudAgent;
    private final InterventionSessionStore sessions;
    private final ScamPlaybookStore playbook;
    private final FraudIntelStore fraudIntel;
    private final ComplianceAgent complianceAgent;
    private final LiteracyAgent literacyAgent;

    public MasterAgent(
            List<Agent> specialistAgents,
            ConversationalAgent fraudAgent,
            InterventionSessionStore sessions,
            ScamPlaybookStore playbook,
            FraudIntelStore fraudIntel,
            ComplianceAgent complianceAgent,
            LiteracyAgent literacyAgent
    ) {
        this.specialistAgents = specialistAgents;
        this.fraudAgent = fraudAgent;
        this.sessions = sessions;
        this.playbook = playbook;
        this.fraudIntel = fraudIntel;
        this.complianceAgent = complianceAgent;
        this.literacyAgent = literacyAgent;
        log.info("MasterAgent initialized with {} specialist agents: {}",
                specialistAgents.size(), specialistAgents.stream().map(Agent::name).toList());
    }

    /**
     * Orchestrate the full multi-agent assessment for one transfer intent.
     * Returns the pipeline result: decision + optional intervention session + report ref.
     */
    public MasterResult orchestrate(AgentContext ctx) {
        String decisionId = "dec_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Run all specialist agents (sequentially for demo stability; parallel in prod)
        List<AgentVerdict> verdicts = new ArrayList<>();
        for (Agent agent : specialistAgents) {
            try {
                AgentVerdict v = agent.assess(ctx);
                verdicts.add(v);
                log.info("[{}] {} verdict={} score={} rule={}",
                        decisionId, agent.name(), v.decision(), v.score(), v.ruleId());
            } catch (Exception e) {
                log.warn("[{}] {} threw exception: {}", decisionId, agent.name(), e.getMessage());
                verdicts.add(AgentVerdict.safeDefault());
            }
        }

        // 2. Fuse verdicts under the trust guardrails
        Decision fused = fuse(verdicts);
        AgentVerdict primaryDriver = pickPrimaryDriver(verdicts, fused);
        log.info("[{}] fused decision={} driven by {} ({})",
                decisionId, fused, primaryDriver.ruleId(), primaryDriver.decision());

        // 3. Resolve
        String reportRef = null;
        String sessionMessage = null;
        String sessionId = null;

        switch (fused) {
            case GREEN -> {
                return MasterResult.green(decisionId, "تمت الموافقة على التحويل.", verdicts);
            }
            case RED -> {
                reportRef = report(ctx, decisionId, Decision.RED, "BLOCKED", primaryDriver);
                String message = primaryDriver.evidence();
                if (primaryDriver.ruleId().contains("MULE")) {
                    message = "تم إيقاف التحويل: الحساب المستفيد مُدرج ضمن قائمة حسابات تمرير الأموال. هذا قرار قطعي لحمايتك.";
                }
                // ComplianceAgent drafts a SAR for the compliance team
                ComplianceAgent.SarDraft sar = complianceAgent.draftSar(ctx, verdicts, reportRef);
                // LiteracyAgent generates a just-in-time micro-lesson
                LiteracyAgent.MicroLesson lesson = literacyAgent.generateLesson(
                        Decision.RED, primaryDriver.ruleId(), ctx.customerRef(),
                        ctx.transfer().amount(), ctx.transfer().beneficiaryIban());
                log.info("[{}] SAR drafted: {} | lesson: {}", decisionId, sar.sarId(), lesson.scamName());
                return MasterResult.red(decisionId, message, reportRef, verdicts, sar, lesson);
            }
            case ORANGE -> {
                // Open the intervention via the FraudAgent (the conversational agent)
                GroundedContext grounded = buildGroundedContext(ctx);
                InterventionSession session = sessions.open(grounded);
                sessionId = session.sessionId();

                InterventionTurn opening;
                try {
                    opening = fraudAgent.intervene(session, null);
                } catch (Exception e) {
                    log.warn("[{}] intervention opening failed, using stub: {}", decisionId, e.getMessage());
                    opening = InterventionTurn.ask("هل يمكنك توضيح سبب هذا التحويل ومن هو المستفيد تحديدًا؟");
                }
                session.recordShieldQuestion(opening.message());
                learnPattern(opening);
                sessionMessage = opening.message();

                report(ctx, decisionId, Decision.ORANGE, "CHALLENGE_ISSUED", primaryDriver);
                return MasterResult.orange(decisionId, sessionMessage, sessionId, verdicts);
            }
            default -> {
                return MasterResult.green(decisionId, "تمت الموافقة على التحويل.", verdicts);
            }
        }
    }

    /**
     * Continue the grey-zone intervention dialogue.
     */
    public MasterReplyResult reply(String sessionId, String answer) {
        InterventionSession session = sessions.get(sessionId);
        if (session == null) {
            return new MasterReplyResult("انتهت صلاحية الجلسة.", Decision.RED, true, List.of());
        }

        session.recordCustomerAnswer(answer);
        InterventionTurn turn;
        try {
            turn = fraudAgent.intervene(session, answer);
        } catch (Exception e) {
            log.warn("[{}] intervene failed, forcing RED: {}", sessionId, e.getMessage());
            turn = InterventionTurn.escalate("تعذّر إكمال التحقق. تم إيقاف التحويل حمايةً لك.", "AI_FAILURE");
        }
        learnPattern(turn);

        boolean wantsToContinue = turn.action() != InterventionTurn.Action.CLEAR
                && turn.action() != InterventionTurn.Action.ESCALATE;

        if (session.turnsExhausted() && wantsToContinue) {
            log.info("[{}] turn limit reached, forcing RED", sessionId);
            sessions.close(sessionId);
            return new MasterReplyResult(
                    "نعتذر، لم نتمكّن من تأكيد سلامة هذا التحويل خلال المحادثة، لذلك أوقفناه حمايةً لك.",
                    Decision.RED, true, List.of());
        }

        return switch (turn.action()) {
            case ASK -> {
                session.recordShieldQuestion(turn.message());
                yield new MasterReplyResult(turn.message(), Decision.ORANGE, false, List.of());
            }
            case CLEAR -> {
                sessions.close(sessionId);
                yield new MasterReplyResult(turn.message(), Decision.GREEN, true, List.of());
            }
            case ESCALATE -> {
                sessions.close(sessionId);
                yield new MasterReplyResult(turn.message(), Decision.RED, true, List.of());
            }
            default -> new MasterReplyResult(turn.message(), Decision.ORANGE, false, List.of());
        };
    }

    // ── Fusion logic ──────────────────────────────────────────────────

    /**
     * Fuse agent verdicts under the trust principle:
     * - Any RED with hard fact → RED
     * - Any RED without hard fact → ORANGE (can't force RED from soft signals)
     * - Any ORANGE → ORANGE
     * - All GREEN → GREEN
     */
    Decision fuse(List<AgentVerdict> verdicts) {
        boolean anyRedHard = verdicts.stream().anyMatch(v -> v.decision() == Decision.RED && v.fromHardFact());
        if (anyRedHard) return Decision.RED;

        boolean anyRedSoft = verdicts.stream().anyMatch(v -> v.decision() == Decision.RED && !v.fromHardFact());
        boolean anyOrange = verdicts.stream().anyMatch(v -> v.decision() == Decision.ORANGE);

        if (anyOrange || anyRedSoft) return Decision.ORANGE;

        return Decision.GREEN;
    }

    private AgentVerdict pickPrimaryDriver(List<AgentVerdict> verdicts, Decision fused) {
        return verdicts.stream()
                .filter(v -> v.decision() == fused)
                .max((a, b) -> Double.compare(a.score(), b.score()))
                .orElse(verdicts.get(0));
    }

    private GroundedContext buildGroundedContext(AgentContext ctx) {
        return new GroundedContext(
                ctx.customerRef(), ctx.transfer(), ctx.behavioral(), ctx.coercion(),
                ctx.serverFacts(), ctx.knownScamPatterns() != null ? ctx.knownScamPatterns() : List.of()
        );
    }

    private void learnPattern(InterventionTurn turn) {
        if (turn.coachingDetected() && turn.observedPattern() != null) {
            playbook.append(turn.observedPattern());
        }
    }

    private String report(AgentContext ctx, String decisionId, Decision decision,
                          String outcome, AgentVerdict driver) {
        String reportRef = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("amount", ctx.transfer().amount());
        snapshot.put("beneficiaryIban", ctx.transfer().beneficiaryIban());
        snapshot.put("driverRule", driver.ruleId());
        snapshot.put("driverScore", driver.score());
        snapshot.put("agents", specialistAgents.stream().map(Agent::name).toList());

        fraudIntel.add(new FraudIntelRecord(
                ctx.eventId(), ctx.customerRef(), ctx.transfer().beneficiaryIban(),
                decision, outcome, snapshot, driver.evidence(), Instant.now()
        ));
        if (decision == Decision.RED) {
            log.info("REPORT-TO-FRAUD-DEPT ref={} customer={} outcome={}", reportRef, ctx.customerRef(), outcome);
        }
        return reportRef;
    }

    // ── Result records ────────────────────────────────────────────────

    public record MasterResult(
            String decisionId,
            Decision decision,
            String action,
            String message,
            String sessionId,
            String question,
            String reportReference,
            List<AgentVerdict> agentVerdicts,
            ComplianceAgent.SarDraft sarDraft,
            LiteracyAgent.MicroLesson lesson
    ) {
        public static MasterResult green(String id, String msg, List<AgentVerdict> v) {
            return new MasterResult(id, Decision.GREEN, "PROCEED", msg, null, null, null, v, null, null);
        }

        public static MasterResult red(String id, String msg, String ref, List<AgentVerdict> v,
                                       ComplianceAgent.SarDraft sar, LiteracyAgent.MicroLesson lesson) {
            return new MasterResult(id, Decision.RED, "BLOCK_AND_REPORT", msg, null, null, ref, v, sar, lesson);
        }

        public static MasterResult red(String id, String msg, String ref, List<AgentVerdict> v) {
            return new MasterResult(id, Decision.RED, "BLOCK_AND_REPORT", msg, null, null, ref, v, null, null);
        }

        public static MasterResult orange(String id, String msg, String sid, List<AgentVerdict> v) {
            return new MasterResult(id, Decision.ORANGE, "STEP_UP_CHALLENGE", msg, sid, msg, null, v, null, null);
        }
    }

    public record MasterReplyResult(
            String message,
            Decision finalDecision,
            boolean resolved,
            List<AgentVerdict> agentVerdicts
    ) {
    }
}
