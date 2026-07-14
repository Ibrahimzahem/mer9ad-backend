package com.hackathon.ra9edhamad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenRouter / OpenAI-compatible AI settings, all overridable via environment. */
@ConfigurationProperties("openrouter")
public class OpenRouterProperties {

    private String apiKey = "";
    private String model = "google/gemini-2.5-flash";
    private String baseUrl = "https://openrouter.ai/api/v1";
    private double temperature = 0.2;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
