package com.clearflow.mcp.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LLMConfig {

    @Value("${clearflow.llm.provider:ollama}")
    private String provider;

    @Value("${clearflow.llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${clearflow.llm.ollama.model:llama3.2}")
    private String ollamaModel;

    @Value("${clearflow.llm.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String openrouterBaseUrl;

    @Value("${clearflow.llm.openrouter.api-key:}")
    private String openrouterApiKey;

    @Value("${clearflow.llm.openrouter.model:meta-llama/llama-3.2-3b-instruct:free}")
    private String openrouterModel;

    @Bean
    public LLMClient llmClient(ObjectMapper objectMapper) {
        if ("openrouter".equalsIgnoreCase(provider)) {
            return new OpenRouterLLMClient(openrouterBaseUrl, openrouterApiKey, openrouterModel, objectMapper);
        }
        OllamaLLMClient ollama = new OllamaLLMClient(ollamaBaseUrl, ollamaModel, objectMapper);
        if ("fallback".equalsIgnoreCase(provider)) {
            OpenRouterLLMClient openRouter = new OpenRouterLLMClient(openrouterBaseUrl, openrouterApiKey, openrouterModel, objectMapper);
            return new FallbackLLMClient(ollama, openRouter);
        }
        return ollama;
    }
}
