package com.hackathon.ra9edhamad.ai;

import com.hackathon.ra9edhamad.domain.GroundedContext;
import com.hackathon.ra9edhamad.domain.InterventionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link ShieldAi} backed by a local Ollama instance running Qwen2.5-7B-Instruct
 * (the best open Arabic model). No customer data ever leaves the bank's server —
 * this is the SAMA privacy story.
 *
 * <p>Setup: install Ollama → {@code ollama pull qwen2.5:7b-instruct} → run the backend.
 * If Ollama is not running, this class degrades gracefully (returns safe defaults),
 * and the {@code StubShieldAi} can be selected instead via config.
 *
 * <p>Endpoint: {@code http://localhost:11434/api/chat} (Ollama's OpenAI-compatible chat API).
 */
public class OllamaShieldAi implements ShieldAi {

    private static final Logger log = LoggerFactory.getLogger(OllamaShieldAi.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String DEFAULT_MODEL = "qwen2.5:7b-instruct";
    private static final String DEFAULT_URL = "http://localhost:11434/api/chat";

    private final HttpClient http;
    private final String model;
    private final String url;

    public OllamaShieldAi() {
        this(DEFAULT_URL, DEFAULT_MODEL);
    }

    public OllamaShieldAi(String url, String model) {
        this.url = url != null ? url : DEFAULT_URL;
        this.model = model != null ? model : DEFAULT_MODEL;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("OllamaShieldAi active (url={}, model={})", this.url, this.model);
    }

    @Override
    public FraudVerdict judge(GroundedContext context) {
        String systemPrompt = """
                أنك خبير في كشف الاحتيال المصرفي والهندسة الاجتماعية. مهمتك تقييم تحويل مصرفي \
                بناءً على الحقائق الموثقة فقط، دون اختلاق معلومات. أعد التقييم بصيغة JSON فقط.

                أعد JSON بالصيغة التالية بالضبط (بدون نص إضافي قبل أو بعد):
                {"fraudLikelihood": 0.0-1.0, "assessment": "تقييم بالعربية", "recommendation": "ALLOW|CHALLENGE|BLOCK"}

                قواعد التقييم:
                - ALLOW: التحويل يبدو سليمًا تمامًا، احتمال الاحتيال أقل من 40%.
                - CHALLENGE: هناك شك معقول، يحتاج تحققًا إضافيًا من العميل.
                - BLOCK: احتمال الاحتيال عالٍ جدًا (75%+) مع وجود حساب جديد وسريع الحركة.
                """;

        String userPrompt = "قيّم هذا التحويل بناءً على الحقائق التالية فقط:\n" + context.toCompactJson();

        try {
            String response = chat(systemPrompt, userPrompt, false);
            return parseVerdict(response);
        } catch (Exception e) {
            log.warn("Ollama judge failed, using safe default: {}", e.getMessage());
            return FraudVerdict.safeDefault();
        }
    }

    @Override
    public InterventionTurn intervene(InterventionSession session, String latestAnswer) {
        String systemPrompt = """
                أنك درع، نظام حماية مصرفية. تتحدث مع عميل قد يكون ضحية احتيال هندسي \
                (شخص على الهاتف يوجّهه). مهمتك:

                1. اسأل أسئلة غير متوقعة (لم يُدرب المحتال العميل عليها مسبقًا).
                2. اقرأ الإجابات بحثًا عن إشارات تلقين: إجابات مكررة حرفيًا، سريعة جدًا، \
                   أو متناقضة مع الحقائق الموثقة.
                3. اعرض التناقض على العميل بلغة عربية بسيطة لكسر سيطرة المحتال.
                4. كن هادئًا، محترمًا، بأسلوب خدمة العملاء — لا تتهم العميل أبدًا.

                أعد JSON بالصيغة التالية فقط:
                {"action": "ASK|CLEAR|ESCALATE", "message": "رسالتك للعميل بالعربية", \
                "coachingDetected": true|false, "observedPattern": "وصف النمط إن وُجد أو null"}

                قواعد القرار:
                - ASK: تحتاج سؤالًا آخر (بحد أقصى 3 أسئلة).
                - CLEAR: العميل أجاب بشكل مقنع ومتسق → التحويل سليم.
                - ESCALATE: اكتُشف تلقين واضح أو إجابات مكررة → التحويل مشبوه.
                """;

        String userPrompt = buildInterventionPrompt(session, latestAnswer);

        try {
            String response = chat(systemPrompt, userPrompt, false);
            return parseInterventionTurn(response);
        } catch (Exception e) {
            log.warn("Ollama intervene failed, using fallback: {}", e.getMessage());
            if (latestAnswer == null) {
                return InterventionTurn.ask("هل يمكنك توضيح سبب هذا التحويل ومن هو المستفيد تحديدًا؟");
            }
            return InterventionTurn.ask("هل تعرف هذا المستفيد شخصيًا؟ كيف ولماذا تعرف حسابه؟");
        }
    }

    // ── Ollama HTTP call ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String chat(String systemPrompt, String userPrompt, boolean stream) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", stream);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.3);
        body.put("options", options);

        String jsonBody = MAPPER.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama HTTP " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> resp = MAPPER.readValue(response.body(), Map.class);
        Map<String, Object> message = (Map<String, Object>) resp.get("message");
        if (message == null) {
            throw new RuntimeException("Ollama response missing 'message'");
        }
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new RuntimeException("Ollama response missing 'content'");
        }
        return content.trim();
    }

    // ── Response parsing ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private FraudVerdict parseVerdict(String response) {
        String json = extractJson(response);
        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            double likelihood = ((Number) map.getOrDefault("fraudLikelihood", 0.5)).doubleValue();
            String assessment = (String) map.getOrDefault("assessment", "تعذّر التقييم.");
            String recStr = (String) map.getOrDefault("recommendation", "CHALLENGE");
            FraudVerdict.Recommendation rec = switch (recStr.toUpperCase().trim()) {
                case "ALLOW" -> FraudVerdict.Recommendation.ALLOW;
                case "BLOCK" -> FraudVerdict.Recommendation.BLOCK;
                default -> FraudVerdict.Recommendation.CHALLENGE;
            };
            return new FraudVerdict(likelihood, assessment, rec);
        } catch (Exception e) {
            log.warn("Failed to parse verdict JSON: {} → safe default", e.getMessage());
            return FraudVerdict.safeDefault();
        }
    }

    @SuppressWarnings("unchecked")
    private InterventionTurn parseInterventionTurn(String response) {
        String json = extractJson(response);
        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            String actionStr = (String) map.getOrDefault("action", "ASK");
            String message = (String) map.getOrDefault("message", "?");
            boolean coaching = Boolean.TRUE.equals(map.get("coachingDetected"));
            String pattern = (String) map.get("observedPattern");
            if ("null".equalsIgnoreCase(pattern)) pattern = null;

            return switch (actionStr.toUpperCase().trim()) {
                case "CLEAR" -> InterventionTurn.clear(message, pattern);
                case "ESCALATE" -> InterventionTurn.escalate(message, pattern);
                default -> InterventionTurn.ask(message, coaching, pattern);
            };
        } catch (Exception e) {
            log.warn("Failed to parse intervention JSON: {} → fallback ASK", e.getMessage());
            return InterventionTurn.ask(response);
        }
    }

    private String buildInterventionPrompt(InterventionSession session, String latestAnswer) {
        StringBuilder sb = new StringBuilder();
        sb.append("السياق الموثق:\n").append(session.context().toCompactJson()).append("\n\n");
        sb.append("سجل المحادثة حتى الآن:\n");
        for (InterventionSession.Turn t : session.history()) {
            sb.append(t.speaker()).append(": ").append(t.text()).append("\n");
        }
        if (latestAnswer != null) {
            sb.append("\nآخر إجابة من العميل: ").append(latestAnswer).append("\n");
        }
        sb.append("\nأنت الآن ");
        sb.append(latestAnswer == null ? "تبدأ المحادثة بسؤال افتتاحي." : "ترد على إجابة العميل.");
        return sb.toString();
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }
}
