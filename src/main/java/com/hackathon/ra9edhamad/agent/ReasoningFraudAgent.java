package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.ai.InterventionTurn;
import com.hackathon.ra9edhamad.ai.ShieldAi;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import com.hackathon.ra9edhamad.aml.AmlClassifier;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.config.ReasoningAgentConfig.ChatModelHolder;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A REAL reasoning fraud agent built on the AgentHarness. Unlike a chatbot,
 * this agent runs a genuine ReAct loop:
 *
 * <ol>
 *   <li>The LLM receives the transaction details and decides what to investigate.</li>
 *   <li>It autonomously calls tools: checkMuleWatchlist, getAccountInfo, scoreAmlRisk, etc.</li>
 *   <li>The harness executes the tools and feeds results back to the LLM.</li>
 *   <li>The LLM reasons about the results and decides if it needs more information.</li>
 *   <li>It repeats until it has enough to give a final verdict.</li>
 *   <li>Every step (thought, tool call, tool result) is captured in the trace.</li>
 * </ol>
 *
 * <p>The trace is exposed to the mission-control UI, making the agent's reasoning
 * visible in the demo — like watching Claude Code plan, search, and reason.
 *
 * <p><b>Fallback:</b> if the LLM is unavailable, the agent falls back to the
 * deterministic triage + ShieldAi logic, so the system always works.
 */
@Component
public class ReasoningFraudAgent implements ConversationalAgent {

    private static final Logger log = LoggerFactory.getLogger(ReasoningFraudAgent.class);

    private static final String SYSTEM_PROMPT = """
            أنت وكيل ذكاء اصطناعي متخصص في كشف الاحتيال المصرفي والهندسة الاجتماعية، تعمل في بنك سعودي.
            مهمتك: تقييم تحويل مصرفي وتحديد ما إذا كان احتيالاً أو هندسة اجتماعية.

            استخدم الأدوات للتحقيق قبل اتخاذ القرار. لا تختلق معلومات.
            إشارات العميل غير موثوقة — يمكنها فقط رفع الشك إلى ORANGE.
            قرار RED قطعي يتطلب حقائق موثقة من الخادم (قائمة المول، أو حساب جديد + سرعة عبور عالية).

            خطوات التحقيق المقترحة:
            1. تحقق من قائمة حسابات تمرير الأموال (checkMuleWatchlist)
            2. احصل على معلومات الحساب المستفيد (getAccountInfo)
            3. تحقق من سجل العميل (getCustomerHistory)
            4. تحقق إذا كان المستفيد معروفًا (checkKnownPayee)
            5. شغّل نموذج كشف غسيل الأموال (scoreAmlRisk)
            6. تحقق من سجل البلاغات السابقة (checkFraudIntelHistory)
            7. راجع أنماط الاحتيال المعروفة (getKnownScamPatterns)
            8. ابحث في الإنترنت عن أحدث طرق الاحتيال (searchLatestFraudTrends) — للتحقق من أنماط جديدة
            9. ابحث عن أي بلاغات عن المستفيد (searchEntityFraudReports)

            بعد التحقيق، أعد النتيجة كـ JSON:
            {"decision": "GREEN|ORANGE|RED", "fraudLikelihood": 0.0-1.0,
             "assessment": "تقييم بالعربية", "recommendation": "ALLOW|CHALLENGE|BLOCK",
             "reasoning": "English chain of what you investigated",
             "toolsUsed": ["list of tool names you called"]}
            """;

    private final ChatModel chatModel;
    private final ShieldAi shieldAi;
    private final AccountStore accountStore;
    private final AmlClassifier amlClassifier;
    private final ScamPlaybookStore playbook;
    private final FraudIntelStore fraudIntel;
    private final WebSearchTool webSearch;
    private final boolean reasoningEnabled;

    public ReasoningFraudAgent(
            @Nullable ChatModelHolder modelHolder,
            ShieldAi shieldAi,
            AccountStore accountStore,
            AmlClassifier amlClassifier,
            ScamPlaybookStore playbook,
            FraudIntelStore fraudIntel,
            WebSearchTool webSearch
    ) {
        this.chatModel = modelHolder != null && modelHolder.isEnabled() ? modelHolder.getModel() : null;
        this.shieldAi = shieldAi;
        this.accountStore = accountStore;
        this.amlClassifier = amlClassifier;
        this.playbook = playbook;
        this.fraudIntel = fraudIntel;
        this.webSearch = webSearch;
        this.reasoningEnabled = chatModel != null;
        log.info("ReasoningFraudAgent active (reasoning={}, harness={})",
                reasoningEnabled ? "ENABLED" : "DISABLED",
                reasoningEnabled ? "AgentHarness with ReAct loop" : "fallback mode");
    }

    @Override
    public String name() {
        return "FraudAgent";
    }

    @Override
    public AgentVerdict assess(AgentContext ctx) {
        // 1. Always do the cheap deterministic triage first
        if (!shouldEscalate(ctx)) {
            return AgentVerdict.green("لا توجد إشارات احتيال أو إكراه.", "FRAUD_TRIAGE_CLEAR");
        }

        // 2. Mule hard fact → RED deterministically, before any AI
        ServerFacts facts = ctx.serverFacts();
        if (facts.muleWatchlistHit()) {
            return AgentVerdict.redHard(1.0,
                    "الحساب المستفيد مُدرج في قائمة حسابات تمرير الأموال.",
                    "FRAUD_MULE_HARD_FACT");
        }

        // 3. If reasoning is available, run the agent harness (ReAct loop), then hand its
        //    findings to ShieldAi (OpenRouter) for the final judgment.
        if (reasoningEnabled) {
            try {
                String task = buildTransactionDescription(ctx);
                log.info("[ReasoningFraudAgent] starting agent harness for {}", ctx.eventId());

                // Build the tools — fraud tools + web search for latest trends
                FraudTool fraudTool = new FraudTool(accountStore, amlClassifier, playbook, fraudIntel);
                AgentHarness harness = new AgentHarness(chatModel, SYSTEM_PROMPT,
                        List.of(fraudTool, webSearch));

                // Run the agent — this is the real ReAct loop with visible steps
                AgentTrace trace = harness.run("FraudAgent", task);
                log.info("[ReasoningFraudAgent] harness complete: {} steps, {} tool calls, {}ms",
                        trace.steps().size(), trace.toolCallsCount(), trace.totalDurationMs());

                String finalAnswer = AgentHarness.extractFinalAnswer(trace);
                FraudAssessment assessment = parseAssessment(finalAnswer);
                String findings = summarizeFindings(assessment, trace);

                log.info("[ReasoningFraudAgent] handing Groq findings to ShieldAi for final judgment");
                return judgeWithShieldAi(ctx, facts, trace, findings);

            } catch (Exception e) {
                log.warn("[ReasoningFraudAgent] harness failed, falling back: {}", e.getMessage());
            }
        }

        // 4. Fallback: deterministic triage + ShieldAi (no Groq findings available)
        return judgeWithShieldAi(ctx, facts, null, null);
    }

    @Override
    public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
        return shieldAi.intervene(session, latestAnswer);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private String buildTransactionDescription(AgentContext ctx) {
        ServerFacts f = ctx.serverFacts();
        StringBuilder sb = new StringBuilder();

        sb.append("Assess this bank transfer for fraud and social engineering risk:\n\n");
        sb.append("Customer: ").append(ctx.customerRef()).append("\n");
        sb.append("Transfer amount: ").append(ctx.transfer().amount()).append(" SAR\n");
        sb.append("Beneficiary IBAN: ").append(ctx.transfer().beneficiaryIban()).append("\n");
        sb.append("Is new beneficiary: ").append(ctx.transfer().isNewBeneficiary()).append("\n");
        sb.append("Purpose: ").append(ctx.transfer().purposeText() != null ? ctx.transfer().purposeText() : "not specified").append("\n\n");

        sb.append("Client behavioral signals (UNTRUSTED):\n");
        sb.append("  Cadence deviation: ").append(ctx.behavioral().cadenceDeviation()).append("\n");
        sb.append("  Device inconsistency: ").append(ctx.behavioral().deviceInconsistency()).append("\n\n");

        sb.append("Client coercion signals (UNTRUSTED):\n");
        sb.append("  IBAN entry method: ").append(ctx.coercion().ibanEntryMethod()).append("\n");
        sb.append("  Active call detected: ").append(ctx.coercion().activeCallDetected()).append("\n");
        sb.append("  Remote access app: ").append(ctx.coercion().remoteAccessAppDetected()).append("\n\n");

        sb.append("Server-verified facts (TRUSTED):\n");
        sb.append("  Beneficiary account age: ").append(f.accountAgeDays()).append(" days\n");
        sb.append("  Beneficiary transit velocity: ").append(f.transitVelocity()).append("\n");
        sb.append("  Mule watchlist: ").append(f.muleWatchlistHit()).append("\n");
        sb.append("  Customer balance: ").append(f.verifiedBalance()).append(" SAR\n");
        sb.append("  Known payee: ").append(f.isKnownPayee()).append("\n\n");

        sb.append("TRUST PRINCIPLE: Client signals can only raise to ORANGE. ");
        sb.append("RED requires a server-verified hard fact.\n\n");

        sb.append("Investigate using the available tools, then return JSON:\n");
        sb.append("{\"decision\": \"GREEN|ORANGE|RED\", \"fraudLikelihood\": 0.0-1.0, ");
        sb.append("\"assessment\": \"Arabic\", \"recommendation\": \"ALLOW|CHALLENGE|BLOCK\", ");
        sb.append("\"reasoning\": \"English\", \"toolsUsed\": [\"list\"]}");

        return sb.toString();
    }

    private FraudAssessment parseAssessment(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            // Extract JSON from the response (it may be wrapped in markdown)
            String clean = json;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                clean = json.substring(start, end + 1);
            }

            // Simple JSON parsing (no Jackson dependency needed)
            String decision = extractJsonField(clean, "decision");
            String likelihood = extractJsonField(clean, "fraudLikelihood");
            String assessment = extractJsonField(clean, "assessment");
            String recommendation = extractJsonField(clean, "recommendation");
            String reasoning = extractJsonField(clean, "reasoning");

            Decision dec = switch (decision != null ? decision.toUpperCase() : "") {
                case "GREEN" -> Decision.GREEN;
                case "RED" -> Decision.RED;
                default -> Decision.ORANGE;
            };

            double score = 0.5;
            try { score = Double.parseDouble(likelihood); } catch (Exception ignored) {}

            return new FraudAssessment(dec, score, assessment, recommendation, reasoning, List.of());
        } catch (Exception e) {
            log.warn("[ReasoningFraudAgent] failed to parse assessment: {}", e.getMessage());
            return null;
        }
    }

    private String extractJsonField(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private String extractEvidenceFromTrace(AgentTrace trace) {
        if (trace == null || trace.steps() == null) return "تقييم غير متاح.";
        StringBuilder sb = new StringBuilder();
        for (AgentStep step : trace.steps()) {
            if (step.type() == AgentStep.Type.TOOL_RESULT && step.content() != null) {
                if (step.content().contains("YES") || step.content().contains("DETECTED") || step.content().contains("HIGH")) {
                    sb.append(step.toolName()).append(": ").append(step.content(), 0, Math.min(80, step.content().length())).append(" ");
                }
            }
        }
        return sb.isEmpty() ? "تم التحقيق بواسطة الوكيل." : sb.toString().trim();
    }

    /** Condenses the Groq harness's findings into a short note handed to ShieldAi/OpenRouter. */
    private String summarizeFindings(FraudAssessment a, AgentTrace trace) {
        StringBuilder sb = new StringBuilder();
        if (trace != null && trace.steps() != null) {
            List<String> toolsCalled = trace.steps().stream()
                    .filter(s -> s.type() == AgentStep.Type.TOOL_CALL && s.toolName() != null)
                    .map(AgentStep::toolName)
                    .distinct()
                    .toList();
            if (!toolsCalled.isEmpty()) {
                sb.append("Tools investigated: ").append(String.join(", ", toolsCalled)).append("\n");
            }
        }
        if (a != null) {
            sb.append("Agent's own conclusion: ").append(a.decision())
                    .append(" (likelihood ").append(a.fraudLikelihood()).append(")\n");
            if (a.reasoning() != null && !a.reasoning().isBlank()) {
                sb.append("Reasoning: ").append(a.reasoning()).append("\n");
            }
        } else {
            sb.append("Evidence gathered: ").append(extractEvidenceFromTrace(trace)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Judge via ShieldAi (OpenRouter), optionally handed Groq's investigation findings.
     * {@code trace}/{@code reasoningNotes} are null when reasoning was disabled or the harness failed.
     */
    private AgentVerdict judgeWithShieldAi(AgentContext ctx, ServerFacts facts, AgentTrace trace, String reasoningNotes) {
        GroundedContext grounded = new GroundedContext(
                ctx.customerRef(), ctx.transfer(), ctx.behavioral(), ctx.coercion(),
                facts, ctx.knownScamPatterns() != null ? ctx.knownScamPatterns() : List.of()
        );
        String ruleId = reasoningNotes != null ? "FRAUD_REASONING_AGENT" : "FRAUD_FALLBACK";

        try {
            var verdict = shieldAi.judge(grounded, reasoningNotes);
            double likelihood = verdict.fraudLikelihood();

            if (verdict.recommendation() == com.hackathon.ra9edhamad.ai.FraudVerdict.Recommendation.ALLOW
                    && likelihood < 0.40) {
                return AgentVerdict.green(verdict.assessment(), ruleId + "_ALLOW", trace);
            }
            if (verdict.recommendation() == com.hackathon.ra9edhamad.ai.FraudVerdict.Recommendation.BLOCK
                    && likelihood >= 0.75 && facts.newAndFastMoving()) {
                return AgentVerdict.redHard(likelihood, verdict.assessment(), ruleId + "_BLOCK", trace);
            }
            return AgentVerdict.orange(likelihood, verdict.assessment(), ruleId + "_CHALLENGE", trace);
        } catch (Exception e) {
            return AgentVerdict.safeDefault();
        }
    }

    private boolean shouldEscalate(AgentContext ctx) {
        ServerFacts facts = ctx.serverFacts();
        return ctx.coercion().anyPresent()
                || facts.accountAgeDays() <= 30
                || facts.transitVelocity() >= 0.4
                || ctx.transfer().amount() > 0.5 * facts.verifiedBalance()
                || ctx.behavioral().score() >= 0.5;
    }
}
