package com.hackathon.ra9edhamad.config;

import com.hackathon.ra9edhamad.ai.OllamaShieldAi;
import com.hackathon.ra9edhamad.ai.OpenRouterShieldAi;
import com.hackathon.ra9edhamad.ai.ShieldAi;
import com.hackathon.ra9edhamad.ai.StubShieldAi;
import com.hackathon.ra9edhamad.aml.AmlClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the AI layer and the AML classifier. AI selection priority:
 * <ol>
 *   <li>{@code SHIELD_AI=ollama} → local Ollama (Qwen2.5) — privacy-first, no data leaves the bank.</li>
 *   <li>{@code OPENROUTER_API_KEY} set → OpenRouter (Gemini/external).</li>
 *   <li>Otherwise → {@link StubShieldAi} (deterministic, offline-safe).</li>
 * </ol>
 *
 * <p>The {@link AmlClassifier} bean loads the trained ONNX model if present;
 * if absent, it degrades to rules-only mode.
 */
@Configuration
@EnableConfigurationProperties(OpenRouterProperties.class)
public class ShieldAiConfig {

    private static final Logger log = LoggerFactory.getLogger(ShieldAiConfig.class);

    @Bean
    public ShieldAi shieldAi(OpenRouterProperties props) {
        String aiChoice = System.getenv("SHIELD_AI");
        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434/api/chat");
        String ollamaModel = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen2.5:7b-instruct");

        if ("ollama".equalsIgnoreCase(aiChoice)) {
            log.info("Shield AI: OllamaShieldAi active (url={}, model={})", ollamaUrl, ollamaModel);
            return new OllamaShieldAi(ollamaUrl, ollamaModel);
        }
        if (props.hasApiKey()) {
            log.info("Shield AI: OpenRouterShieldAi active (model={})", props.getModel());
            RestClient restClient = RestClient.builder()
                    .baseUrl(props.getBaseUrl())
                    .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .build();
            return new OpenRouterShieldAi(restClient, props);
        }
        log.info("Shield AI: StubShieldAi active (deterministic demo mode — set SHIELD_AI=ollama for local LLM)");
        return new StubShieldAi();
    }

    @Bean(destroyMethod = "close")
    public AmlClassifier amlClassifier() {
        AmlClassifier clf = new AmlClassifier();
        if (clf.isLoaded()) {
            log.info("AML classifier: ONNX model loaded — active");
        } else {
            log.info("AML classifier: model not found — AmlAgent will use rules-only mode");
        }
        return clf;
    }
}
