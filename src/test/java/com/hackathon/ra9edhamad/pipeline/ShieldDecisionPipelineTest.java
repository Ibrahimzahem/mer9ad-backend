package com.hackathon.ra9edhamad.pipeline;

import com.hackathon.ra9edhamad.ai.FraudVerdict;
import com.hackathon.ra9edhamad.ai.InterventionTurn;
import com.hackathon.ra9edhamad.ai.ShieldAi;
import com.hackathon.ra9edhamad.ai.StubShieldAi;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import com.hackathon.ra9edhamad.domain.BehavioralSignals;
import com.hackathon.ra9edhamad.domain.CoercionSignals;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.InterventionSessionStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.TransferSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Triage + resolve outcomes for the three seeded scenarios, driven by the deterministic stub. */
class ShieldDecisionPipelineTest {

    private AccountStore accounts;
    private ScamPlaybookStore playbook;
    private FraudIntelStore intel;
    private ShieldDecisionPipeline pipeline;

    @BeforeEach
    void setUp() {
        accounts = new AccountStore();
        playbook = new ScamPlaybookStore();
        intel = new FraudIntelStore();
        pipeline = new ShieldDecisionPipeline(accounts, new StubShieldAi(), playbook, intel,
                new InterventionSessionStore());
    }

    private BehavioralSignals benignBehavioral() {
        return new BehavioralSignals(0.1, 5, 0.1, "population");
    }

    private CoercionSignals benignCoercion() {
        return new CoercionSignals("typed", 1000, false, false, "typed", 5000);
    }

    private TransferSummary transfer(String iban, double amount) {
        return new TransferSummary(iban, amount, true, "مصاريف عائلية", "typed");
    }

    @Test
    void establishedPayeeIsClearedToGreenByTriage() {
        PipelineResult r = pipeline.assess("evt1", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_ESTABLISHED, 100), benignBehavioral(), benignCoercion());
        assertThat(r.decision()).isEqualTo(Decision.GREEN);
    }

    @Test
    void muleBeneficiaryIsBlockedToRedWithHardFact() {
        PipelineResult r = pipeline.assess("evt2", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_MULE, 100), benignBehavioral(), benignCoercion());
        assertThat(r.decision()).isEqualTo(Decision.RED);
        assertThat(r.reportReference()).isNotBlank();
    }

    @Test
    void muleForcesRedEvenWhenAiVotesAllow() {
        // An AI that always says "all clear" — the mule hard fact must override it.
        ShieldAi permissiveAi = new ShieldAi() {
            @Override
            public FraudVerdict judge(GroundedContext context) {
                return new FraudVerdict(0.0, "يبدو سليمًا", FraudVerdict.Recommendation.ALLOW);
            }

            @Override
            public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
                return InterventionTurn.ask("?");
            }
        };
        ShieldDecisionPipeline p = new ShieldDecisionPipeline(accounts, permissiveAi, playbook, intel,
                new InterventionSessionStore());

        PipelineResult r = p.assess("evtM", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_MULE, 100), benignBehavioral(), benignCoercion());
        assertThat(r.decision()).isEqualTo(Decision.RED);
        assertThat(r.reportReference()).isNotBlank();
    }

    @Test
    void newHighTransitOpensOrangeIntervention() {
        PipelineResult r = pipeline.assess("evt3", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_NEW_HIGH_TRANSIT, 100), benignBehavioral(), benignCoercion());
        assertThat(r.decision()).isEqualTo(Decision.ORANGE);
        assertThat(r.sessionId()).isNotBlank();
        assertThat(r.question()).isNotBlank();
    }

    @Test
    void coachedAnswersEscalateOrangeToRed() {
        PipelineResult r = pipeline.assess("evt4", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_NEW_HIGH_TRANSIT, 100), benignBehavioral(), benignCoercion());
        String session = r.sessionId();

        // The customer is given several messages before the transfer is held.
        ReplyResult first = pipeline.reply(session, "مصاريف عائلية");
        assertThat(first.resolved()).isFalse();

        ReplyResult second = pipeline.reply(session, "مصاريف عائلية");
        assertThat(second.resolved()).isFalse();

        ReplyResult third = pipeline.reply(session, "مصاريف عائلية");
        assertThat(third.resolved()).isTrue();
        assertThat(third.finalDecision()).isEqualTo(Decision.RED);
        // a fresh manipulation pattern should have been learned
        assertThat(playbook.patterns().size()).isGreaterThan(3);
    }

    @Test
    void turnLimitForcesRedWhenAiKeepsAsking() {
        // An AI that never resolves — always ASK. The pipeline's hard cap must still force RED.
        ShieldAi alwaysAsks = new ShieldAi() {
            @Override
            public FraudVerdict judge(GroundedContext context) {
                return new FraudVerdict(0.6, "يحتاج تحقق", FraudVerdict.Recommendation.CHALLENGE);
            }

            @Override
            public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
                return InterventionTurn.ask("سؤال آخر؟");
            }
        };
        ShieldDecisionPipeline p = new ShieldDecisionPipeline(accounts, alwaysAsks, playbook, intel,
                new InterventionSessionStore());

        PipelineResult r = p.assess("evtCap", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_NEW_HIGH_TRANSIT, 100), benignBehavioral(), benignCoercion());
        assertThat(r.decision()).isEqualTo(Decision.ORANGE);
        String session = r.sessionId();

        // opening already asked (turn 1); two replies keep it ASKing (turns 2 and 3)...
        assertThat(p.reply(session, "أي شيء").resolved()).isFalse();
        assertThat(p.reply(session, "أي شيء").resolved()).isFalse();
        // ...the next reply is past the cap: the pipeline forces RED even though the AI said ASK.
        ReplyResult capped = p.reply(session, "أي شيء");
        assertThat(capped.resolved()).isTrue();
        assertThat(capped.finalDecision()).isEqualTo(Decision.RED);
    }

    @Test
    void blatantScamAnswerEscalatesToRed() {
        // The customer calmly describes a classic scam. Content must win over confident delivery.
        PipelineResult r = pipeline.assess("evtScam", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_NEW_HIGH_TRANSIT, 100), benignBehavioral(), benignCoercion());

        ReplyResult reply = pipeline.reply(r.sessionId(),
                "سأحوّل لأمير نيجيري تعرفت عليه عبر الإنترنت وسيضاعف لي المبلغ عند وصوله للسعودية");
        assertThat(reply.resolved()).isTrue();
        assertThat(reply.finalDecision()).isEqualTo(Decision.RED);
        // the fresh scam pattern is learned into the playbook
        assertThat(playbook.patterns().size()).isGreaterThan(3);
    }

    @Test
    void convincingAnswerResolvesOrangeToGreen() {
        PipelineResult r = pipeline.assess("evt5", AccountStore.DEMO_CUSTOMER,
                transfer(AccountStore.IBAN_NEW_HIGH_TRANSIT, 100), benignBehavioral(), benignCoercion());

        ReplyResult reply = pipeline.reply(r.sessionId(),
                "هذا حساب صديقي خالد الذي أعرفه منذ سنوات، وأسدد له ثمن سيارة اشتريتها منه الأسبوع الماضي.");
        assertThat(reply.resolved()).isTrue();
        assertThat(reply.finalDecision()).isEqualTo(Decision.GREEN);
    }
}
