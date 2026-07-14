package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The financial-literacy specialist agent. After Shield catches a scam (ORANGE or RED),
 * this agent generates a just-in-time, pattern-specific Arabic micro-lesson —
 * delivered at the "teachable moment," the 60 seconds after a near-miss when the
 * victim is most receptive.
 *
 * <p>In reasoning mode, the LLM writes a personalized lesson tailored to the exact
 * scam pattern and the customer's situation. In fallback mode, a template-based
 * lesson is generated from the detected pattern.
 *
 * <p>The lesson is sourced from the self-growing scam playbook, so the literacy
 * content improves as Shield learns new patterns. This is the bridge to the
 * Financial Literacy track AND the financial-inclusion thesis.
 */
@Component
public class LiteracyAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(LiteracyAgent.class);

    private final ScamPlaybookStore playbook;
    private final ReasoningLiteracyAssistant reasoningAssistant;
    private final boolean reasoningEnabled;

    public LiteracyAgent(ScamPlaybookStore playbook,
                         @Nullable ReasoningLiteracyAssistant reasoningAssistant) {
        this.playbook = playbook;
        this.reasoningAssistant = reasoningAssistant;
        this.reasoningEnabled = reasoningAssistant != null;
        log.info("LiteracyAgent active (reasoning={})", reasoningEnabled ? "ENABLED" : "DISABLED — template fallback");
    }

    @Override
    public String name() {
        return "LiteracyAgent";
    }

    @Override
    public AgentVerdict assess(AgentContext ctx) {
        return AgentVerdict.green("وكالة التثقيف جاهزة.", "LITERACY_READY");
    }

    /**
     * Generate a just-in-time micro-lesson after a scam was caught.
     *
     * @param decision        the outcome (ORANGE cleared to GREEN, or RED)
     * @param detectedPattern the scam pattern that was detected (from the agent verdicts)
     * @param customerRef     the customer (for personalization)
     * @param amount          the transfer amount
     * @param beneficiaryIban the beneficiary IBAN
     * @return a structured micro-lesson
     */
    public MicroLesson generateLesson(Decision decision, String detectedPattern,
                                       String customerRef, double amount, String beneficiaryIban) {
        log.info("[LiteracyAgent] generating lesson for customer={} pattern={} decision={}",
                customerRef, detectedPattern, decision);

        if (reasoningEnabled) {
            try {
                String situation = buildSituationDescription(decision, detectedPattern, customerRef, amount, beneficiaryIban);
                LiteracyAssessment assessment = reasoningAssistant.generateLesson(situation);
                if (assessment != null && assessment.scamName() != null) {
                    List<String> signs = assessment.signs() != null ? assessment.signs() : List.of();
                    if (signs.isEmpty()) signs = buildSigns(inferScamName(detectedPattern));
                    String action = assessment.action() != null ? assessment.action() : buildAction(inferScamName(detectedPattern));
                    log.info("[LiteracyAgent] LLM lesson generated: {} signs={}", assessment.scamName(), signs.size());
                    return new MicroLesson(
                            assessment.scamName(),
                            assessment.lesson(),
                            signs,
                            action,
                            decision == Decision.RED ? "blocked" : "intercepted",
                            playbook.patterns().size()
                    );
                }
            } catch (Exception e) {
                log.warn("[LiteracyAgent] LLM lesson failed, using template: {}", e.getMessage());
            }
        }

        return generateTemplateLesson(decision, detectedPattern, customerRef);
    }

    private String buildSituationDescription(Decision decision, String detectedPattern,
                                              String customerRef, double amount, String beneficiaryIban) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a just-in-time financial literacy lesson for this situation:\n\n");
        sb.append("Outcome: ").append(decision == Decision.RED ? "Transfer BLOCKED" : "Transfer INTERCEPTED").append("\n");
        sb.append("Detected pattern: ").append(detectedPattern).append("\n");
        sb.append("Customer: ").append(customerRef).append("\n");
        sb.append("Amount: ").append(amount).append(" SAR\n");
        sb.append("Beneficiary: ").append(beneficiaryIban != null ? beneficiaryIban : "unknown").append("\n");
        sb.append("Known scam patterns in playbook: ").append(playbook.patterns().size()).append("\n\n");
        sb.append("The lesson must be in Arabic, personalized to this exact scam type. ");
        sb.append("Include 3 specific warning signs the customer should watch for next time, ");
        sb.append("and one clear action they should take.");
        return sb.toString();
    }

    private MicroLesson generateTemplateLesson(Decision decision, String detectedPattern, String customerRef) {
        String scamName = inferScamName(detectedPattern);
        String lesson = buildLesson(scamName, detectedPattern, decision);
        List<String> signs = buildSigns(scamName);
        String action = buildAction(scamName);

        log.info("[LiteracyAgent] template lesson generated: {}", scamName);
        return new MicroLesson(
                scamName, lesson, signs, action,
                decision == Decision.RED ? "blocked" : "intercepted",
                playbook.patterns().size()
        );
    }

    private String inferScamName(String pattern) {
        if (pattern == null) return "احتيال مصرفي";
        String p = pattern.toUpperCase();
        if (p.contains("STRUCTURING") || p.contains("SMURF")) return "تجزئة المعاملات (Smurfing)";
        if (p.contains("MULE")) return "حسابات تمرير الأموال (Mule)";
        if (p.contains("ROUND_TRIP")) return "دوران الأموال (Round-tripping)";
        if (p.contains("TRANSIT")) return "عبور الأموال السريع (Rapid Transit)";
        if (p.contains("CROSS_BORDER")) return "التحويلات الدولية المشبوهة";
        if (p.contains("مصاريف عائلية") || p.contains("COACHED")) return "الاحتيال المصرفي المزيف (Bank Investigator)";
        if (p.contains("ضاعف") || p.contains("DOUBLE") || p.contains("PROFIT")) return "احتيال الدفع المسبق (Prepayment)";
        if (p.contains("أمير") || p.contains("PRINCE") || p.contains("LOTTERY")) return "احتيال الأمير/الميراث";
        if (p.contains("CRYPTO") || p.contains("BITCOIN")) return "احتيال العملات الرقمية";
        return "احتيال بالهندسة الاجتماعية";
    }

    private String buildLesson(String scamName, String pattern, Decision decision) {
        String outcome = decision == Decision.RED
                ? "تم إيقاف التحويل وحماية أموالك"
                : "تم اعتراض التحويل ومراجعته قبل إتمامه";
        return String.format(
                "هذا الاحتيال الذي %s يُسمى «%s». " +
                "لحمايتك في المستقبل، تعرّف على علاماته وتعلّم كيف تكتشفه مبكرًا. " +
                "تذكّر: البنك لن يطلب منك أبدًا تحويل أموالك إلى حساب «آمن»، ولن يسألك عن رمزك السري.",
                outcome, scamName);
    }

    private List<String> buildSigns(String scamName) {
        if (scamName.contains("Mule") || scamName.contains("MULE"))
            return List.of(
                    "الحساب المستفيد حديث الإنشاء وغير معروف لك",
                    "المتصل يدّعي أنه من البنك ويطلب تحويلًا عاجلاً",
                    "ضغط وإلحاح على إتمام التحويل فورًا");
        if (scamName.contains("Smurfing") || scamName.contains("STRUCTURE"))
            return List.of(
                    "طلب تقسيم المبلغ إلى تحويلات صغيرة متعددة",
                    "تحويلات متكررة لنفس الحساب في وقت قصير",
                    "مبالغ تحت حد الإبلاغ (10,000 ريال)");
        if (scamName.contains("Bank Investigator"))
            return List.of(
                    "متصل ينتحل صفة موظف البنك أو الشرطة",
                    "يطلب تحويل أموالك إلى حساب «حماية»",
                    "يخلق شعورًا بالعجلة والخطر الكاذب");
        if (scamName.contains("Prepayment") || scamName.contains("الدفع المسبق"))
            return List.of(
                    "وعد بأرباح أو مضاعفة المبلغ مقابل تحويل مسبق",
                    "جهة مجهولة تطلب تحويلًا سريعًا",
                    "ضمانات «مضمونة» لعائدات غير منطقية");
        if (scamName.contains("العملات الرقمية") || scamName.contains("CRYPTO"))
            return List.of(
                    "وعد بأرباح سريعة من العملات الرقمية",
                    "طلب تحويل إلى منصة غير معروفة",
                    "ضغط للاستثمار قبل «انتهاء الفرصة»");
        return List.of(
                "متصل غير معروف يطلب تحويلًا عاجلاً",
                "الحساب المستفيد جديد وغير موثوق",
                "ضغط وإلحاف على إتمام العملية فورًا");
    }

    private String buildAction(String scamName) {
        return "أنهِ المكالمة فورًا واتصل بالبنك على الرقم المكتوب خلف بطاقتك. لا تحوّل أي مبلغ بناءً على مكالمة هاتفية.";
    }

    public record MicroLesson(
            String scamName,
            String lesson,
            List<String> signs,
            String action,
            String outcome,
            int playbookSize
    ) {
    }
}
