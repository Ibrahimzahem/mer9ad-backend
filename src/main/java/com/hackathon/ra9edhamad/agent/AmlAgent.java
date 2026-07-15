package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.aml.AmlClassifier;
import com.hackathon.ra9edhamad.aml.AmlFeatures;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import com.hackathon.ra9edhamad.domain.Transaction;
import com.hackathon.ra9edhamad.config.ReasoningAgentConfig.ChatModelHolder;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AML specialist agent. Two modes:
 *
 * <ol>
 *   <li><b>Reasoning mode</b> (when LangChain4j + Ollama available): a REAL ReAct agent
 *       that investigates laundering using AML-specific tools (detectStructuring,
 *       analyzeAccountRisk, checkRoundTripping, runAmlClassifier). The LLM decides
 *       what to investigate and in what order.</li>
 *   <li><b>Fallback mode</b> (no Ollama): deterministic rules + the trained ONNX
 *       classifier. Always works.</li>
 * </ol>
 *
 * <p>Different tools from the FraudAgent → genuinely different investigation.
 * The LLM is loaded ONCE in Ollama; both agents share it.
 */
@Component
public class AmlAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AmlAgent.class);

    private static final int STRUCTURING_COUNT = 3;
    private static final double STRUCTURING_THRESHOLD = 10_000.0;
    private static final double TRANSIT_RATIO = 0.80;
    private static final double CLASSIFIER_RED_THRESHOLD = 0.75;
    private static final double CLASSIFIER_ORANGE_THRESHOLD = 0.40;

    private static final String AML_SYSTEM_PROMPT = """
            أنت وكيل ذكاء اصطناعي متخصص في كشف غسيل الأموال (AML)، تعمل في بنك سعودي.
            مهمتك: تقييم تحويل مصرفي وتحديد ما إذا كان جزءًا من نمط غسيل أموال.

            استخدم أدوات AML للتحقيق بأسلوب ReAct:
            1. detectStructuring — تحقق من تجزئة المعاملات (تحويلات صغيرة متعددة في نفس اليوم)
            2. analyzeAccountRisk — تحليل سرعة عبور الأموال وعمر الحساب
            3. checkRoundTripping — تحقق من دوران الأموال (A→B→A)
            4. countInternationalTransfers — عد التحويلات الدولية
            5. runAmlClassifier — شغّل نموذج الذكاء الاصطناعي المدرب
            6. getTransactionHistory — راجع سجل المعاملات الكامل
            7. checkFraudIntelLog — تحقق من البلاغات السابقة
            8. searchLatestAmlTrends — ابحث في الإنترنت عن أحدث طرق غسيل الأموال والأنماط الجديدة
            9. searchEntityFraudReports — ابحث عن بلاغات عامة عن المستفيد

            لا تختلق معلومات. قرارات RED تحتاج نمط محدد موثوق:
            - STRUCTURING (3+ تحويلات صغيرة في نفس اليوم)
            - ROUND_TRIPPING (A→B→A pattern)
            - RAPID_TRANSIT + classifier score ≥ 0.75

            أعد النتيجة كـ JSON:
            {"decision": "GREEN|ORANGE|RED", "launderingScore": 0.0-1.0,
             "assessment": "بالعربية", "reasoning": "English chain",
             "toolsUsed": ["list"], "launderingPattern": "STRUCTURING|RAPID_TRANSIT|ROUND_TRIPPING|CROSS_BORDER|NONE"}
            """;

    private final AmlClassifier classifier;
    private final ChatModel chatModel;
    private final AccountStore accountStore;
    private final FraudIntelStore fraudIntel;
    private final WebSearchTool webSearch;
    private final boolean reasoningEnabled;

    public AmlAgent(
            AmlClassifier classifier,
            @Nullable ChatModelHolder modelHolder,
            AccountStore accountStore,
            FraudIntelStore fraudIntel,
            WebSearchTool webSearch
    ) {
        this.classifier = classifier;
        this.chatModel = modelHolder != null && modelHolder.isEnabled() ? modelHolder.getModel() : null;
        this.accountStore = accountStore;
        this.fraudIntel = fraudIntel;
        this.webSearch = webSearch;
        this.reasoningEnabled = chatModel != null;
        log.info("AmlAgent active (reasoning={}, harness={})",
                reasoningEnabled ? "ENABLED" : "DISABLED",
                reasoningEnabled ? "AgentHarness with ReAct loop" : "rules+classifier fallback");
    }

    @Override
    public String name() {
        return "AmlAgent";
    }

    @Override
    public AgentVerdict assess(AgentContext ctx) {
        if (reasoningEnabled) {
            try {
                String task = buildAmlDescription(ctx);
                log.info("[AmlAgent] starting agent harness for {}", ctx.eventId());

                AmlTool amlTool = new AmlTool(accountStore, classifier, fraudIntel);
                AgentHarness harness = new AgentHarness(chatModel, AML_SYSTEM_PROMPT,
                        List.of(amlTool, webSearch));

                AgentTrace trace = harness.run("AmlAgent", task);
                log.info("[AmlAgent] harness complete: {} steps, {} tool calls, {}ms",
                        trace.steps().size(), trace.toolCallsCount(), trace.totalDurationMs());

                String finalAnswer = AgentHarness.extractFinalAnswer(trace);
                AmlAssessment assessment = parseAssessment(finalAnswer);

                if (assessment != null) {
                    return mapAssessmentToVerdict(assessment, ctx, trace);
                }

                log.warn("[AmlAgent] could not parse assessment, using fallback");
            } catch (Exception e) {
                log.warn("[AmlAgent] harness failed, falling back to rules: {}", e.getMessage());
            }
        }
        return fallbackAssess(ctx);
    }

    private AmlAssessment parseAssessment(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            String clean = json;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                clean = json.substring(start, end + 1);
            }

            String decision = extractJsonField(clean, "decision");
            String score = extractJsonField(clean, "launderingScore");
            String assessment = extractJsonField(clean, "assessment");
            String pattern = extractJsonField(clean, "launderingPattern");

            Decision dec = switch (decision != null ? decision.toUpperCase() : "") {
                case "GREEN" -> Decision.GREEN;
                case "RED" -> Decision.RED;
                default -> Decision.ORANGE;
            };

            double s = 0.5;
            try { s = Double.parseDouble(score); } catch (Exception ignored) {}

            return new AmlAssessment(dec, s, assessment, null, List.of(),
                    pattern != null ? pattern : "NONE");
        } catch (Exception e) {
            log.warn("[AmlAgent] failed to parse assessment: {}", e.getMessage());
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

    // ── Reasoning path ────────────────────────────────────────────────

    private String buildAmlDescription(AgentContext ctx) {
        ServerFacts f = ctx.serverFacts();
        StringBuilder sb = new StringBuilder();
        sb.append("Assess this transfer for MONEY LAUNDERING risk (not fraud):\n\n");
        sb.append("Customer: ").append(ctx.customerRef()).append("\n");
        sb.append("Amount: ").append(ctx.transfer().amount()).append(" SAR\n");
        sb.append("Beneficiary IBAN: ").append(ctx.transfer().beneficiaryIban()).append("\n");
        sb.append("Beneficiary account age: ").append(f.accountAgeDays()).append(" days\n");
        sb.append("Beneficiary transit velocity: ").append(f.transitVelocity()).append("\n");
        sb.append("Mule watchlist hit: ").append(f.muleWatchlistHit()).append("\n\n");

        // Pre-compute the classifier score so the LLM has it immediately
        double precomputedScore = precomputeClassifierScore(ctx);
        if (precomputedScore >= 0) {
            sb.append(String.format("PRE-COMPUTED AML CLASSIFIER SCORE: %.2f (%s)\n",
                    precomputedScore,
                    precomputedScore >= 0.75 ? "HIGH RISK" : precomputedScore >= 0.40 ? "SUSPICIOUS" : "LOW RISK"));
            sb.append("This is from the trained RandomForest model (22 features, ONNX).\n\n");
        }

        // Include transaction history summary
        if (ctx.transactionHistory() != null && !ctx.transactionHistory().isEmpty()) {
            sb.append("Transaction history summary:\n");
            long outCount = ctx.transactionHistory().stream()
                    .filter(t -> "OUT".equalsIgnoreCase(t.direction())).count();
            long subThreshold = ctx.transactionHistory().stream()
                    .filter(t -> "OUT".equalsIgnoreCase(t.direction()) && t.amount() < 10_000).count();
            sb.append("  Total outgoing transfers: ").append(outCount).append("\n");
            sb.append("  Sub-threshold (<10k SAR): ").append(subThreshold).append("\n\n");
        }

        sb.append("Use the AML tools to investigate further. Check for: structuring (many small transfers), ");
        sb.append("rapid transit (pass-through accounts), round-tripping (A→B→A), and run the classifier.\n");
        sb.append("You can also check the fraud-intelligence log for prior reports on this IBAN.\n\n");
        sb.append("Return JSON:\n");
        sb.append("{\"decision\": \"GREEN|ORANGE|RED\", \"launderingScore\": 0.0-1.0, ");
        sb.append("\"assessment\": \"Arabic explanation\", \"reasoning\": \"English investigation chain\", ");
        sb.append("\"toolsUsed\": [\"list\"], \"launderingPattern\": \"STRUCTURING|RAPID_TRANSIT|ROUND_TRIPPING|CROSS_BORDER|NONE\"}");
        return sb.toString();
    }

    private double precomputeClassifierScore(AgentContext ctx) {
        try {
            List<AmlFeatures.HistoryEntry> senderHistory = buildSenderHistory(ctx);
            List<AmlFeatures.HistoryEntry> receiverHistory = buildReceiverHistory(ctx);
            boolean receiverAlsoSends = !receiverHistory.isEmpty()
                    && hasOutgoing(ctx.customer(), ctx.transfer().beneficiaryIban());
            float[] features = AmlFeatures.compute(
                    ctx.transfer().amount(), senderHistory, receiverHistory, receiverAlsoSends, false);
            return classifier.predictLaundering(features);
        } catch (Exception e) {
            return -1.0;
        }
    }

    private AgentVerdict mapAssessmentToVerdict(AmlAssessment a, AgentContext ctx, AgentTrace trace) {
        Decision decision = a.decision() != null ? a.decision() : Decision.GREEN;
        double score = a.launderingScore();
        String pattern = a.launderingPattern() != null ? a.launderingPattern() : "NONE";

        boolean hardFact = decision == Decision.RED
                && (pattern.contains("STRUCTURING") || pattern.contains("ROUND_TRIPPING"));

        if (decision == Decision.RED && !hardFact) {
            double classifierScore = precomputeClassifierScore(ctx);
            if (classifierScore < 0.75) {
                log.info("[AmlAgent] agent voted RED for {} but no hard pattern + classifier={} → ORANGE",
                        pattern, String.format("%.2f", classifierScore));
                decision = Decision.ORANGE;
            }
        }

        String evidence = a.assessment() != null ? a.assessment() : "تقييم غسيل الأموال غير متاح.";
        String ruleId = "AML_REASONING_" + pattern;

        if (decision == Decision.RED && hardFact) {
            return AgentVerdict.redHard(score, evidence, ruleId, trace);
        } else if (decision == Decision.GREEN) {
            return AgentVerdict.green(evidence, ruleId, trace);
        } else {
            return AgentVerdict.orange(score, evidence, ruleId, trace);
        }
    }

    // ── Fallback path (deterministic rules + classifier) ─────────────

    private AgentVerdict fallbackAssess(AgentContext ctx) {
        List<String> firedRules = new ArrayList<>();
        double maxScore = 0.0;

        var senderHistory = buildSenderHistory(ctx);
        var receiverHistory = buildReceiverHistory(ctx);
        double amount = ctx.transfer().amount();

        // Rule 1: Structuring
        int subThresholdCount = countSubThresholdToSameBeneficiary(senderHistory, ctx.transfer().beneficiaryIban(), amount);
        if (subThresholdCount >= STRUCTURING_COUNT) {
            firedRules.add("STRUCTURING");
            maxScore = Math.max(maxScore, Math.min(1.0, 0.5 + subThresholdCount * 0.1));
            log.info("[AmlAgent-fallback] STRUCTURING: {} sub-threshold transfers", subThresholdCount);
        }

        // Rule 2: Rapid transit
        if (ctx.serverFacts().transitVelocity() >= TRANSIT_RATIO) {
            firedRules.add("RAPID_TRANSIT");
            maxScore = Math.max(maxScore, 0.7);
            log.info("[AmlAgent-fallback] RAPID_TRANSIT: velocity={}", ctx.serverFacts().transitVelocity());
        }

        // Rule 3: Round-tripping
        if (detectRoundTripping(ctx)) {
            firedRules.add("ROUND_TRIPPING");
            maxScore = Math.max(maxScore, 0.7);
            log.info("[AmlAgent-fallback] ROUND_TRIPPING detected");
        }

        // Rule 4: Cross-border
        int crossBorderCount = countCrossBorder(ctx.customer());
        if (crossBorderCount >= 3) {
            firedRules.add("CROSS_BORDER_VELOCITY");
            maxScore = Math.max(maxScore, Math.min(1.0, 0.4 + crossBorderCount * 0.1));
        }

        // Classifier
        boolean receiverAlsoSends = !receiverHistory.isEmpty() && hasOutgoing(ctx.customer(), ctx.transfer().beneficiaryIban());
        float[] features = AmlFeatures.compute(amount, senderHistory, receiverHistory, receiverAlsoSends, false);
        double classifierScore = classifier.predictLaundering(features);

        if (classifierScore >= 0) {
            maxScore = Math.max(maxScore, classifierScore);
            if (classifierScore >= CLASSIFIER_RED_THRESHOLD && !firedRules.isEmpty()) {
                firedRules.add("CLASSIFIER_HIGH");
            } else if (classifierScore >= CLASSIFIER_ORANGE_THRESHOLD) {
                firedRules.add("CLASSIFIER_SUSPICIOUS");
            }
        }

        if (firedRules.isEmpty() && (classifierScore < 0 || classifierScore < CLASSIFIER_ORANGE_THRESHOLD)) {
            return AgentVerdict.green("لا توجد أنماط غسيل أموال.", "AML_CLEAR");
        }

        String evidence = buildEvidence(firedRules, maxScore, classifierScore);
        String ruleId = firedRules.isEmpty() ? "AML_CLASSIFIER_ONLY" : String.join("+", firedRules);
        boolean hardFact = !firedRules.isEmpty() && maxScore >= 0.7;

        return hardFact
                ? AgentVerdict.redHard(maxScore, evidence, ruleId)
                : AgentVerdict.orange(maxScore, evidence, ruleId);
    }

    // ── Fallback helpers ──────────────────────────────────────────────

    private List<AmlFeatures.HistoryEntry> buildSenderHistory(AgentContext ctx) {
        if (ctx.transactionHistory() == null) return List.of();
        return ctx.transactionHistory().stream()
                .filter(e -> "OUT".equalsIgnoreCase(e.direction()))
                .map(e -> new AmlFeatures.HistoryEntry(e.date(), e.counterpartyIban(), e.amount(), e.paymentFormat()))
                .toList();
    }

    private List<AmlFeatures.HistoryEntry> buildReceiverHistory(AgentContext ctx) {
        if (ctx.transactionHistory() == null) return List.of();
        String benIban = ctx.transfer().beneficiaryIban();
        return ctx.transactionHistory().stream()
                .filter(e -> benIban != null && benIban.equals(e.counterpartyIban()))
                .map(e -> new AmlFeatures.HistoryEntry(e.date(), e.counterpartyIban(), e.amount(), e.paymentFormat()))
                .toList();
    }

    private int countSubThresholdToSameBeneficiary(List<AmlFeatures.HistoryEntry> history, String iban, double currentAmount) {
        // Structuring = multiple sub-threshold transfers to the same beneficiary on the SAME DAY
        // (not spread across months). Find the date with the most sub-threshold transfers.
        Map<String, Integer> byDate = new java.util.HashMap<>();
        for (var e : history) {
            if (iban != null && iban.equals(e.counterpartyIban()) && e.amount() < STRUCTURING_THRESHOLD) {
                byDate.merge(e.date(), 1, Integer::sum);
            }
        }
        int maxSameDay = byDate.values().stream().max(Integer::compare).orElse(0);
        // The current transfer counts toward today's count if it's sub-threshold
        if (currentAmount < STRUCTURING_THRESHOLD) maxSameDay++;
        return maxSameDay;
    }

    private boolean detectRoundTripping(AgentContext ctx) {
        if (ctx.customer() == null || ctx.transfer().beneficiaryIban() == null) return false;
        return ctx.customer().transactions() != null && ctx.customer().transactions().stream()
                .anyMatch(t -> "IN".equals(t.direction()) && ctx.transfer().beneficiaryIban().equals(t.iban()));
    }

    private int countCrossBorder(CustomerProfile customer) {
        if (customer == null || customer.beneficiaries() == null) return 0;
        return (int) customer.beneficiaries().stream()
                .filter(b -> b.name() != null && !isArabicName(b.name()))
                .count();
    }

    private boolean isArabicName(String name) {
        if (name == null) return true;
        for (char c : name.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC) return true;
        }
        return false;
    }

    private boolean hasOutgoing(CustomerProfile customer, String iban) {
        if (customer == null || customer.transactions() == null) return false;
        return customer.transactions().stream()
                .anyMatch(t -> "OUT".equals(t.direction()) && iban != null && iban.equals(t.iban()));
    }

    private String buildEvidence(List<String> rules, double score, double classifierScore) {
        StringBuilder sb = new StringBuilder();
        if (rules.contains("STRUCTURING")) sb.append("اكتُشفت نمط تجزئة: عدة تحويلات صغيرة لنفس المستفيد. ");
        if (rules.contains("RAPID_TRANSIT")) sb.append("الحساب المستفيد يُمرر الأموال بسرعة عالية. ");
        if (rules.contains("ROUND_TRIPPING")) sb.append("اكتُشف نمط دوران الأموال. ");
        if (rules.contains("CROSS_BORDER_VELOCITY")) sb.append("عدد غير معتاد من التحويلات الدولية. ");
        if (classifierScore >= 0) sb.append(String.format("نموذج الكشف: %.0f%%. ", classifierScore * 100));
        if (sb.isEmpty()) sb.append("اشتباه في غسيل أموال محتمل.");
        return sb.toString().trim();
    }
}
