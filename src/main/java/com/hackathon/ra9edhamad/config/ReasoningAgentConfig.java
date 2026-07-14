package com.hackathon.ra9edhamad.config;

import com.hackathon.ra9edhamad.agent.*;
import com.hackathon.ra9edhamad.aml.AmlClassifier;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the agent harness. All agents share the SAME chat model (loaded ONCE).
 *
 * <p>Set {@code SHIELD_AI=ollama} for local Ollama, or {@code SHIELD_AI=groq}
 * for Groq's hosted models (OpenAI-compatible, 500 tokens/s, free tier).
 *
 * <p>Agents use the {@link com.hackathon.ra9edhamad.agent.AgentHarness} — a real
 * ReAct loop where the agent plans, calls tools, observes results, and reasons
 * in a visible chain. Not a chatbot.
 */
@Configuration
public class ReasoningAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(ReasoningAgentConfig.class);

    @Bean
    public ReasoningLiteracyAssistant reasoningLiteracyAssistant(ChatModelHolder modelHolder) {
        if (!modelHolder.isEnabled()) return null;
        log.info("Building Literacy reasoning agent...");
        return AiServices.builder(ReasoningLiteracyAssistant.class)
                .chatModel(modelHolder.getModel())
                .build();
    }

    /**
     * Holds the single chat model (loaded ONCE, shared by all agents).
     * Supports Ollama (local) or Groq (hosted, OpenAI-compatible).
     */
    @Bean
    public ChatModelHolder chatModelHolder() {
        String aiChoice = System.getenv("SHIELD_AI");
        if (aiChoice == null || aiChoice.isBlank()) {
            log.info("Agent harness: DISABLED (set SHIELD_AI=ollama or SHIELD_AI=groq)");
            return new ChatModelHolder(null, false);
        }

        try {
            ChatModel model = switch (aiChoice.toLowerCase()) {
                case "ollama" -> buildOllamaModel();
                case "groq" -> buildGroqModel();
                default -> {
                    log.warn("Unknown SHIELD_AI={} (use 'ollama' or 'groq')", aiChoice);
                    yield null;
                }
            };
            if (model == null) return new ChatModelHolder(null, false);
            model.chat("Say OK");
            log.info("Chat model connected. Agent harness ready — all agents will use the ReAct loop.");
            return new ChatModelHolder(model, true);
        } catch (Exception e) {
            log.warn("Chat model not reachable — agents will use deterministic fallback: {}", e.getMessage());
            return new ChatModelHolder(null, false);
        }
    }

    private ChatModel buildOllamaModel() {
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen2.5:7b-instruct");
        log.info("Connecting to Ollama at {} (model={})...", baseUrl, model);
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private ChatModel buildGroqModel() {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY not set — get one at console.groq.com/keys");
        }
        String model = System.getenv().getOrDefault("GROQ_MODEL", "llama-3.3-70b-versatile");
        log.info("Connecting to Groq (model={})...", model);
        return OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    public record ChatModelHolder(ChatModel model, boolean enabled) {
        public boolean isEnabled() { return enabled; }
        public ChatModel getModel() { return model; }
    }
}
