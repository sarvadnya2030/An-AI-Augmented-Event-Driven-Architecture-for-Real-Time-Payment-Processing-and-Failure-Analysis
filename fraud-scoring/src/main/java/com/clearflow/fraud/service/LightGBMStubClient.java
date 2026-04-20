package com.clearflow.fraud.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class LightGBMStubClient {

    private final WebClient webClient;

    public LightGBMStubClient(WebClient.Builder builder,
                              @Value("${fraud.model-server-url:http://localhost:8091}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Retry(name = "lgbm")
    @CircuitBreaker(name = "lgbm", fallbackMethod = "fallbackPredict")
    public Mono<ModelScoreResponse> predict(double[] features, Map<String, Object> metadata) {
        return webClient.post()
                .uri("/predict")
                .bodyValue(Map.of("features", features, "metadata", metadata))
                .retrieve()
                .bodyToMono(ModelScoreResponse.class);
    }

    Mono<ModelScoreResponse> fallbackPredict(double[] features, Map<String, Object> metadata, Throwable throwable) {
        return Mono.just(new ModelScoreResponse(0.55d, Map.of("fallback", 1.0d), "fallback-cb"));
    }

    public record ModelScoreResponse(double score, Map<String, Double> featureImportance, String modelVersion) {
    }
}
