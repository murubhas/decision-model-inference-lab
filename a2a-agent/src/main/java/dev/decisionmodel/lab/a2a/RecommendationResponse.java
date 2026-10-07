package dev.decisionmodel.lab.a2a;

import java.time.Duration;

import dev.decisionmodel.lab.guardrail.TokenUsage;

public record RecommendationResponse(
        String text,
        String model,
        TokenUsage usage,
        Duration latency) {

    public RecommendationResponse {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (usage == null) {
            throw new IllegalArgumentException("usage must not be null");
        }
        if (latency == null || latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be null or negative");
        }
    }
}
