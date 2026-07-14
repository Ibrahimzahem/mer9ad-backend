package com.hackathon.ra9edhamad.pipeline;

import com.hackathon.ra9edhamad.ai.FraudVerdict;
import com.hackathon.ra9edhamad.ai.InterventionTurn;
import com.hackathon.ra9edhamad.ai.ShieldAi;
import com.hackathon.ra9edhamad.domain.Account;
import com.hackathon.ra9edhamad.domain.BehavioralSignals;
import com.hackathon.ra9edhamad.domain.CoercionSignals;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.FraudIntelRecord;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import com.hackathon.ra9edhamad.domain.InterventionSessionStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import com.hackathon.ra9edhamad.domain.TransferSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The core Shield decision pipeline: aggregate signals (kept in separate families) → cheap
 * deterministic triage → AI fraud judge (only on ESCALATE) → resolve with a strict guardrail
 * → log. The triage is NOT the detector; it only spares the AI the obvious-clean cases.
 */
@Service
public class ShieldDecisionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ShieldDecisionPipeline.class);

    // Triage thresholds (tunable constants).
    private static final int NEW_ACCOUNT_DAYS = 30;
    private static final double TRANSIT_TRIGGER = 0.4;
    private static final double AMOUNT_FRACTION_TRIGGER = 0.5;
    private static final double BEHAVIORAL_TRIGGER = 0.5;

    // Resolve-guardrail thresholds.
    private static final double ALLOW_LIKELIHOOD_CEILING = 0.40;
    private static final double BLOCK_LIKELIHOOD_FLOOR = 0.75;

    private static final String MULE_RED_MESSAGE =
            "تم إيقاف التحويل: الحساب المستفيد مُدرج ضمن قائمة حسابات تمرير الأموال. هذا قرار قطعي لحمايتك.";

    private static final String TURN_LIMIT_RED_MESSAGE =
            "نعتذر، لم نتمكّن من تأكيد سلامة هذا التحويل خلال المحادثة، لذلك أوقفناه حمايةً لك. "
                    + "إن كان أحدهم يوجّهك عبر الهاتف الآن، ننصحك بإنهاء المكالمة والتواصل مع البنك على الرقم "
                    + "المدوّن خلف بطاقتك. نطمئنك أنه لم يُخصم أي مبلغ.";

    private final AccountStore accountStore;
    private final ShieldAi shieldAi;
    private final ScamPlaybookStore playbook;
    private final FraudIntelStore fraudIntel;
    private final InterventionSessionStore sessions;

    public ShieldDecisionPipeline(AccountStore accountStore, ShieldAi shieldAi,
                                  ScamPlaybookStore playbook, FraudIntelStore fraudIntel,
                                  InterventionSessionStore sessions) {
        this.accountStore = accountStore;
        this.shieldAi = shieldAi;
        this.playbook = playbook;
        this.fraudIntel = fraudIntel;
        this.sessions = sessions;
    }

    public PipelineResult assess(String eventId, String customerRef, TransferSummary transfer,
                                 BehavioralSignals behavioral, CoercionSignals coercion) {
        String decisionId = "dec_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Aggregate the server-verified facts (the client never sends these).
        ServerFacts facts = resolveFacts(customerRef, transfer);

        // 2. Cheap deterministic triage. CLEAR -> GREEN immediately, AI never called.
        if (!shouldEscalate(transfer, behavioral, coercion, facts)) {
            log.info("[{}] triage=CLEAR -> GREEN (customer={}, iban={})", decisionId, customerRef, transfer.beneficiaryIban());
            return PipelineResult.green(decisionId, "تمت الموافقة على التحويل.");
        }

        // 3. Deterministic hard stop: a confirmed mule is an irreversible RED on its own — it
        // never depends on (or even waits for) the AI verdict.
        if (facts.muleWatchlistHit()) {
            log.info("[{}] triage=ESCALATE, muleWatchlistHit -> RED (deterministic, AI not consulted)", decisionId);
            String reportRef = report(eventId, customerRef, facts, Decision.RED, "BLOCKED",
                    "حساب المستفيد مُدرج في قائمة حسابات تمرير الأموال (mule)",
                    signalsSnapshot(transfer, behavioral, coercion, facts));
            return PipelineResult.red(decisionId, MULE_RED_MESSAGE, reportRef);
        }

        // 4. AI fraud judge — the actual detector — over grounded facts only.
        GroundedContext context = new GroundedContext(customerRef, transfer, behavioral, coercion, facts, playbook.patterns());
        FraudVerdict verdict = shieldAi.judge(context);
        log.info("[{}] triage=ESCALATE, AI verdict={} likelihood={}", decisionId, verdict.recommendation(), verdict.fraudLikelihood());

        // 5. Resolve with a strict guardrail.
        if (verdict.recommendation() == FraudVerdict.Recommendation.ALLOW
                && verdict.fraudLikelihood() < ALLOW_LIKELIHOOD_CEILING) {
            return PipelineResult.green(decisionId, "تمت الموافقة على التحويل.");
        }

        // The only AI-dependent RED: a brand-new, fast-moving account the AI also votes BLOCK on.
        if (verdict.recommendation() == FraudVerdict.Recommendation.BLOCK
                && verdict.fraudLikelihood() >= BLOCK_LIKELIHOOD_FLOOR
                && facts.newAndFastMoving()) {
            String reportRef = report(eventId, customerRef, facts, Decision.RED,
                    "BLOCKED", "حساب جديد جدًا وعالي الخطورة مع تصويت النموذج بالحظر",
                    signalsSnapshot(transfer, behavioral, coercion, facts));
            return PipelineResult.red(decisionId, verdict.assessment(), reportRef);
        }

        // 6. Everything else -> ORANGE: open the grey-zone intervention dialogue.
        InterventionSession session = sessions.open(context);
        InterventionTurn opening = shieldAi.intervene(session, null);
        session.recordShieldQuestion(opening.message());
        learnPattern(opening);
        report(eventId, customerRef, facts, Decision.ORANGE, "CHALLENGE_ISSUED", null,
                signalsSnapshot(transfer, behavioral, coercion, facts));
        return PipelineResult.orange(decisionId, opening.message(), session.sessionId(), opening.message());
    }

    public ReplyResult reply(String sessionId, String answer) {
        InterventionSession session = sessions.get(sessionId);
        if (session == null) {
            return ReplyResult.resolved("انتهت صلاحية الجلسة أو أنها غير موجودة.", Decision.RED);
        }

        session.recordCustomerAnswer(answer);
        InterventionTurn turn = shieldAi.intervene(session, answer);
        learnPattern(turn);

        ServerFacts facts = session.context().serverFacts();
        String eventId = "evt_session_" + sessionId;

        // Hard cap (applies even to the live AI): once the turns are spent, we do NOT let the
        // dialogue continue. If the AI still wants to keep asking, the pipeline forces a
        // protective RED itself. A decisive CLEAR or ESCALATE from the AI is still honored below.
        boolean wantsToContinue = turn.action() != InterventionTurn.Action.CLEAR
                && turn.action() != InterventionTurn.Action.ESCALATE;
        if (session.turnsExhausted() && wantsToContinue) {
            log.info("[{}] turn limit reached, AI still asking -> RED (hard cap)", sessionId);
            sessions.close(sessionId);
            report(eventId, session.customerRef(), facts, Decision.RED, "CHALLENGE_FAILED",
                    turn.observedPattern(), snapshotFromContext(session));
            return ReplyResult.resolved(TURN_LIMIT_RED_MESSAGE, Decision.RED);
        }

        switch (turn.action()) {
            case ASK -> {
                session.recordShieldQuestion(turn.message());
                return ReplyResult.ask(turn.message());
            }
            case CLEAR -> {
                sessions.close(sessionId);
                report(eventId, session.customerRef(), facts, Decision.GREEN, "PROCEEDED_SAFE",
                        turn.observedPattern(), snapshotFromContext(session));
                return ReplyResult.resolved(turn.message(), Decision.GREEN);
            }
            case ESCALATE -> {
                sessions.close(sessionId);
                report(eventId, session.customerRef(), facts, Decision.RED, "CHALLENGE_FAILED",
                        turn.observedPattern(), snapshotFromContext(session));
                return ReplyResult.resolved(turn.message(), Decision.RED);
            }
            default -> {
                return ReplyResult.ask(turn.message());
            }
        }
    }

    // --- helpers ---

    private ServerFacts resolveFacts(String customerRef, TransferSummary transfer) {
        CustomerProfile customer = accountStore.customer(customerRef);
        double balance = customer != null ? customer.verifiedBalance() : 0.0;
        String history = customer != null ? customer.historySummary() : "لا يوجد سجل متاح لهذا العميل.";
        boolean known = customer != null && customer.knowsPayee(transfer.beneficiaryIban());

        Account acc = accountStore.account(transfer.beneficiaryIban());
        if (acc == null) {
            // Unknown beneficiary: no derogatory record on file, so treat the server facts as
            // neutral/benign (an established-looking account, never a hard fact). Suspicion then
            // comes only from real signals — client coercion, amount, behavior — not from the
            // mere absence of seed data. Keeps ad-hoc IBANs from all funnelling into ORANGE.
            return new ServerFacts(transfer.beneficiaryIban(), 365, 0.15, false, balance, known, history);
        }
        return new ServerFacts(transfer.beneficiaryIban(), acc.accountAgeDays(), acc.transitVelocity(),
                acc.muleWatchlistHit(), balance, known, history);
    }

    private boolean shouldEscalate(TransferSummary transfer, BehavioralSignals behavioral,
                                   CoercionSignals coercion, ServerFacts facts) {
        // Note: "new beneficiary" alone is NOT a trigger (every add is new).
        return coercion.anyPresent()
                || facts.accountAgeDays() <= NEW_ACCOUNT_DAYS
                || facts.transitVelocity() >= TRANSIT_TRIGGER
                || transfer.amount() > AMOUNT_FRACTION_TRIGGER * facts.verifiedBalance()
                || behavioral.score() >= BEHAVIORAL_TRIGGER;
    }

    /** Append a freshly observed manipulation pattern to the growing playbook. */
    private void learnPattern(InterventionTurn turn) {
        if (turn.coachingDetected() && turn.observedPattern() != null) {
            playbook.append(turn.observedPattern());
        }
    }

    private String report(String eventId, String customerRef, ServerFacts facts, Decision decision,
                          String outcome, String pattern, Map<String, Object> snapshot) {
        String reportRef = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        fraudIntel.add(new FraudIntelRecord(eventId, customerRef, facts.beneficiaryIban(), decision,
                outcome, snapshot, pattern, Instant.now()));
        if (decision == Decision.RED) {
            log.info("REPORT-TO-FRAUD-DEPT ref={} customer={} iban={} outcome={}",
                    reportRef, customerRef, facts.beneficiaryIban(), outcome);
        }
        return reportRef;
    }

    private Map<String, Object> signalsSnapshot(TransferSummary transfer, BehavioralSignals behavioral,
                                                CoercionSignals coercion, ServerFacts facts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", transfer.amount());
        m.put("isNewBeneficiary", transfer.isNewBeneficiary());
        m.put("purposeEntryMethod", transfer.purposeEntryMethod());
        m.put("ibanEntryMethod", coercion.ibanEntryMethod());
        m.put("activeCallDetected", coercion.activeCallDetected());
        m.put("remoteAccessAppDetected", coercion.remoteAccessAppDetected());
        m.put("maxIdleGapMs", coercion.maxIdleGapMs());
        m.put("behavioralScore", behavioral.score());
        m.put("beneficiaryAccountAgeDays", facts.accountAgeDays());
        m.put("beneficiaryTransitVelocity", facts.transitVelocity());
        m.put("muleWatchlistHit", facts.muleWatchlistHit());
        return m;
    }

    private Map<String, Object> snapshotFromContext(InterventionSession session) {
        GroundedContext c = session.context();
        return signalsSnapshot(c.transfer(), c.behavioral(), c.coercion(), c.serverFacts());
    }
}
