package com.hackathon.ra9edhamad.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.hackathon.ra9edhamad.config.OpenRouterProperties;
import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Live AI over OpenRouter's OpenAI-compatible chat-completions API. Every prompt grounds the
 * model strictly on the provided verified facts and asks for strict JSON only; parsing is
 * defensive and falls back to a safe default.
 */
public class OpenRouterShieldAi implements ShieldAi {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterShieldAi.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final OpenRouterProperties props;

    public OpenRouterShieldAi(RestClient restClient, OpenRouterProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    private static final String JUDGE_SYSTEM = """
            أنت محرك كشف احتيال في بنك. مهمتك تقييم تحويل مالي للكشف عن الهندسة الاجتماعية (الاحتيال بالتحويل المُصرّح به).
            القاعدة الصارمة: استدلّ فقط على "الحقائق المتحقق منها" و"الإشارات" المعطاة في رسالة المستخدم. لا تختلق أي سبب أو معلومة غير موجودة.
            انتبه لأنماط التلاعب المعروفة المُرفقة. أعد JSON فقط بدون أي نص أو علامات ```، بالشكل التالي تحديدًا:
            {"fraudLikelihood": <رقم بين 0 و 1>, "assessment": "<شرح موجز بالعربية>", "recommendation": "ALLOW" | "CHALLENGE" | "BLOCK"}
            """;

    private static final String INTERVENE_SYSTEM = """
            أنت موظف حماية في خدمة عملاء البنك، تتحدث مع عميل قد يكون واقعًا تحت توجيه محتال أثناء تحويل مالي.
            أسلوبك مهني ومهذّب وواضح، دافئ باعتدال — كموظف خدمة عملاء محترف، لا صديق حميم ولا محقّق. تجنّب المبالغة في العاطفة
            والرموز التعبيرية والعبارات المفرطة في اللطف. جُمل قصيرة، لهجة رسمية ومطمئنة.

            مبادئ الحديث:
            - افتح بجملة مهذّبة توضّح أن الهدف هو التأكد من سلامة العملية لحماية العميل، دون اتهام.
            - انشر الوعي باقتضاب: أشِر إلى أن بعض التحويلات تتم بتوجيه من طرف آخر عبر الهاتف، وأن الغرض قد يُملى على العميل.
            - اطرح سؤالًا واحدًا فقط، مهنيًا و"خارج النص" يصعب على المحتال توقّعه.
            - افترض حُسن النية في الأسلوب وأبقِ لهجتك لطيفة، لكن لا تُصدّق مضمونًا احتياليًا لمجرد أنه قيل بثقة وهدوء.
              قيّم *ما يقوله* العميل لا *كيف* يقوله.
            - امنح CLEAR فقط إذا كان الغرض مشروعًا وواقعيًا ونابعًا من العميل وخاليًا من مؤشرات الاحتيال أدناه. إذا ظهرت أيٌّ
              من هذه المؤشرات في كلامه فلا تمنح CLEAR إطلاقًا مهما بدا مطمئنًا:
                • وعدٌ بمضاعفة المبلغ أو بعائد/أرباح أو باسترجاع أكثر مما حوّل.
                • أمير أو ميراث أو جائزة أو يانصيب أو مبلغ من الخارج مقابل رسوم.
                • شخص لم يلتقِ به العميل وجهًا لوجه، أو تعرّف عليه عبر الإنترنت/واتساب/وسائل التواصل، أو علاقة عاطفية عن بُعد.
                • جهة تنتحل صفة (بنك، حكومة، شرطة، شركة شحن، دعم فني) تطلب التحويل أو "تحديث البيانات".
                • عملات رقمية أو فوركس أو "فرصة استثمار" بربح مؤكد عبر جهة مجهولة.
                • استعجال أو ضغط أو تهديد، أو أن العميل يحوّل نيابةً عن طرف آخر أو بتوجيهٍ منه.
            - عند ظهور مؤشر واضح: انشر الوعي باقتضاب واطرح سؤالًا يكشف الحقيقة؛ فإن تأكّد المؤشر أو أصرّ العميل رغم التنبيه،
              انتقل إلى ESCALATE ولو مبكرًا. لا تنتظر نفاد المحاولات مع احتيالٍ جليّ.
            - في غياب هذه المؤشرات كن متساهلًا: تفسير معقول نابع من العميل يكفي لمنح CLEAR، وامنحه عدة محاولات (حتى نحو
              أربع رسائل) قبل الحظر.
            - عند ESCALATE اجعل رسالتك مهنية وحامية: اعتذر بإيجاز، أوقف التحويل لحماية العميل، وانصحه بإنهاء المكالمة والتواصل مع
              البنك على الرقم المدوّن خلف بطاقته، وطمئنه أنه لم يُخصم أي مبلغ. لا تُشعره بالذنب.

            استدلّ فقط على الحقائق والإشارات وتاريخ المحادثة المعطاة. أعد JSON فقط بدون أي نص أو علامات ```، بالشكل التالي:
            {"action": "ASK" | "CLEAR" | "ESCALATE", "message": "<عربية مهنية>", "coachingDetected": <true|false>, "observedPattern": <نص أو null>}
            """;

    @Override
    public FraudVerdict judge(GroundedContext context) {
        String user = "الحقائق والإشارات (استدلّ عليها فقط):\n" + context.toCompactJson();
        try {
            String content = chat(JUDGE_SYSTEM, user);
            JsonNode node = MAPPER.readTree(stripFences(content));
            double likelihood = node.path("fraudLikelihood").asDouble(0.5);
            String assessment = node.path("assessment").asText("");
            FraudVerdict.Recommendation rec = FraudVerdict.Recommendation.valueOf(
                    node.path("recommendation").asText("CHALLENGE").trim().toUpperCase());
            return new FraudVerdict(likelihood, assessment, rec);
        } catch (Exception e) {
            log.warn("judge() falling back to safe default: {}", e.toString());
            return FraudVerdict.safeDefault();
        }
    }

    @Override
    public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
        StringBuilder user = new StringBuilder();
        user.append("الحقائق والإشارات (استدلّ عليها فقط):\n")
                .append(session.context().toCompactJson()).append("\n\n");
        user.append("أنماط التلاعب المعروفة: ")
                .append(String.join(" | ", session.context().knownPatterns())).append("\n\n");
        user.append("تاريخ المحادثة:\n");
        session.history().forEach(t -> user.append("- ").append(t.speaker()).append(": ").append(t.text()).append("\n"));
        user.append("\nإجابة العميل الأخيرة: ").append(latestAnswer == null ? "(لا يوجد بعد — اطرح سؤال الافتتاح)" : latestAnswer);

        try {
            String content = chat(INTERVENE_SYSTEM, user.toString());
            JsonNode node = MAPPER.readTree(stripFences(content));
            InterventionTurn.Action action = InterventionTurn.Action.valueOf(
                    node.path("action").asText("ASK").trim().toUpperCase());
            String message = node.path("message").asText("");
            boolean coaching = node.path("coachingDetected").asBoolean(false);
            String pattern = node.hasNonNull("observedPattern") ? node.get("observedPattern").asText() : null;
            if (pattern != null && (pattern.isBlank() || pattern.equalsIgnoreCase("null"))) {
                pattern = null;
            }
            return new InterventionTurn(action, message, coaching, pattern);
        } catch (Exception e) {
            log.warn("intervene() falling back to ASK: {}", e.toString());
            return InterventionTurn.ask("هل يمكنك أن تشرح لي بكلماتك سبب هذا التحويل ومن صاحب الحساب؟");
        }
    }

    private String chat(String system, String user) {
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "temperature", props.getTemperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                )
        );

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("empty response from OpenRouter");
        }
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    /** Strip ```json / ``` fences and surrounding noise, leaving the JSON object. */
    static String stripFences(String raw) {
        if (raw == null) {
            return "{}";
        }
        String s = raw.trim();
        s = s.replaceAll("(?s)```(?:json)?", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
