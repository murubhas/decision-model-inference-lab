package dev.decisionmodel.lab.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.TokenUsage;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GuardrailTelemetryTest {

    @Test
    void recordsDecisionLatencyTokensFailuresAndRecommendationUsage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GuardrailTelemetry telemetry = new GuardrailTelemetry(registry, GuardrailEnforcement.SHADOW);
        GuardrailEvaluation evaluation = new GuardrailEvaluation(
                "llm-bedrock",
                "stub-model",
                Map.of("prompt_injection", new NoulAnswer(0.91)),
                new TokenUsage(14, 3),
                Duration.ofMillis(25));
        SemanticPathResult pathResult = new SemanticPathResult(
                SemanticPath.LLM,
                List.of(evaluation),
                0,
                Optional.empty());

        telemetry.recordDecision(
                GuardrailBoundary.INPUT,
                GuardrailAction.BLOCK,
                List.of("prompt_injection:block"),
                pathResult);
        telemetry.recordProviderFailure(GuardrailBoundary.OUTPUT, new IllegalStateException("offline"));
        telemetry.recordRecommendation(new RecommendationResponse(
                "Use p5e.",
                "stub-model",
                new TokenUsage(100, 20),
                Duration.ofMillis(80)));
        telemetry.recordRecommendationFailure(new IllegalArgumentException("bad response"));

        assertEquals(1.0, registry.get("decision_inference.guardrail.decisions")
                .tags(
                        "boundary", "input",
                        "path", "llm",
                        "action", "block",
                        "enforcement", "shadow",
                        "selected_provider", "llm-bedrock",
                        "fallback", "not_applicable")
                .counter()
                .count());
        assertEquals(25.0, registry.get("decision_inference.guardrail.provider.duration")
                .tags(
                        "boundary", "input",
                        "path", "llm",
                        "provider", "llm-bedrock",
                        "selected", "true")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(14.0, registry.get("decision_inference.guardrail.tokens")
                .tags(
                        "boundary", "input",
                        "path", "llm",
                        "provider", "llm-bedrock",
                        "direction", "input")
                .counter()
                .count());
        assertEquals(1.0, registry.get("decision_inference.guardrail.failures")
                .tags(
                        "boundary", "output",
                        "enforcement", "shadow",
                        "failure_type", "IllegalStateException")
                .counter()
                .count());
        assertEquals(1.0, registry.get("decision_inference.recommendation.completions")
                .tags("outcome", "success", "failure_type", "none")
                .counter()
                .count());
        assertEquals(1.0, registry.get("decision_inference.recommendation.completions")
                .tags("outcome", "failure", "failure_type", "IllegalArgumentException")
                .counter()
                .count());
        assertEquals(100.0, registry.get("decision_inference.recommendation.tokens")
                .tag("direction", "input")
                .counter()
                .count());
    }
}
