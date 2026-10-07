package dev.decisionmodel.lab.a2a;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GuardrailTelemetry {

    private static final Logger LOG = Logger.getLogger(GuardrailTelemetry.class);

    private final MeterRegistry registry;
    private final GuardrailEnforcement enforcement;

    @Inject
    public GuardrailTelemetry(
            MeterRegistry registry,
            @ConfigProperty(name = "guardrail.enforcement", defaultValue = "SHADOW") GuardrailEnforcement enforcement) {
        this.registry = registry;
        this.enforcement = enforcement;
    }

    public void recordDecision(
            GuardrailBoundary boundary,
            GuardrailAction action,
            List<String> reasons,
            SemanticPathResult result) {
        String boundaryTag = tag(boundary);
        String pathTag = tag(result.path());
        GuardrailEvaluation selected = result.selectedEvaluation();
        String fallbackTag = result.hybridAssessment()
                .map(assessment -> tag(assessment.reason()))
                .orElse("not_applicable");

        registry.counter(
                "decision_inference.guardrail.decisions",
                "boundary", boundaryTag,
                "path", pathTag,
                "action", tag(action),
                "enforcement", tag(enforcement),
                "selected_provider", selected.provider(),
                "fallback", fallbackTag)
                .increment();

        Duration pathLatency = Duration.ZERO;
        for (int index = 0; index < result.evaluations().size(); index++) {
            GuardrailEvaluation evaluation = result.evaluations().get(index);
            pathLatency = pathLatency.plus(evaluation.latency());
            String selectedTag = Boolean.toString(result.contributes(index));
            registry.timer(
                    "decision_inference.guardrail.provider.duration",
                    "boundary", boundaryTag,
                    "path", pathTag,
                    "provider", evaluation.provider(),
                    "selected", selectedTag)
                    .record(evaluation.latency());
            registry.counter(
                    "decision_inference.guardrail.tokens",
                    "boundary", boundaryTag,
                    "path", pathTag,
                    "provider", evaluation.provider(),
                    "direction", "input")
                    .increment(evaluation.usage().inputTokens());
            registry.counter(
                    "decision_inference.guardrail.tokens",
                    "boundary", boundaryTag,
                    "path", pathTag,
                    "provider", evaluation.provider(),
                    "direction", "output")
                    .increment(evaluation.usage().outputTokens());
        }
        registry.timer(
                "decision_inference.guardrail.path.duration",
                "boundary", boundaryTag,
                "path", pathTag,
                "fallback", fallbackTag)
                .record(pathLatency);

        LOG.infof(
                "guardrailDecision boundary=%s path=%s action=%s enforcement=%s selectedProvider=%s "
                        + "selectedModel=%s fallback=%s llmQuestions=%s uncertainQuestions=%s reasons=%s answers=%s",
                boundary,
                result.path(),
                action,
                enforcement,
                selected.provider(),
                selected.model(),
                fallbackTag,
                result.hybridAssessment().map(assessment -> assessment.llmQuestionIds()).orElse(List.of()),
                result.hybridAssessment().map(assessment -> assessment.uncertainQuestionIds()).orElse(List.of()),
                String.join(",", reasons),
                selected.answers());
    }

    public void recordProviderFailure(GuardrailBoundary boundary, RuntimeException exception) {
        registry.counter(
                "decision_inference.guardrail.failures",
                "boundary", tag(boundary),
                "enforcement", tag(enforcement),
                "failure_type", exception.getClass().getSimpleName())
                .increment();
    }

    public void recordRecommendation(RecommendationResponse response) {
        registry.counter(
                "decision_inference.recommendation.completions",
                "outcome", "success",
                "failure_type", "none")
                .increment();
        registry.timer("decision_inference.recommendation.duration").record(response.latency());
        registry.counter(
                "decision_inference.recommendation.tokens",
                "direction", "input")
                .increment(response.usage().inputTokens());
        registry.counter(
                "decision_inference.recommendation.tokens",
                "direction", "output")
                .increment(response.usage().outputTokens());
    }

    public void recordRecommendationFailure(RuntimeException exception) {
        registry.counter(
                "decision_inference.recommendation.completions",
                "outcome", "failure",
                "failure_type", exception.getClass().getSimpleName())
                .increment();
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

}
