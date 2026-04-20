package com.clearflow.mcp.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tries Ollama first; if Ollama returns an error string or throws, falls back to OpenRouter.
 */
public class FallbackLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLLMClient.class);

    private final OllamaLLMClient ollama;
    private final OpenRouterLLMClient openRouter;

    public FallbackLLMClient(OllamaLLMClient ollama, OpenRouterLLMClient openRouter) {
        this.ollama = ollama;
        this.openRouter = openRouter;
    }

    @Override
    public String chat(List<LLMMessage> messages) {
        try {
            String result = ollama.chat(messages);
            if (result != null && !result.startsWith("Ollama unavailable")) {
                return result;
            }
            log.warn("Ollama unavailable or returned error — falling back to OpenRouter");
        } catch (Exception e) {
            log.warn("Ollama threw exception — falling back to OpenRouter: {}", e.getMessage());
        }
        return openRouter.chat(messages);
    }

    @Override
    public String providerName() {
        return "ollama/" + ollama.providerName().replace("ollama/", "") + " (fallback: openrouter)";
    }
}
