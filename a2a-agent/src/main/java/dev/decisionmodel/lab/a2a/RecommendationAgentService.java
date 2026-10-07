package dev.decisionmodel.lab.a2a;

import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecommendationAgentService implements RecommendationGateway {

    private final RecommendationAssistant assistant;
    private final String configuredModel;
    private final GuardrailTelemetry telemetry;

    @Inject
    public RecommendationAgentService(
            RecommendationAssistant assistant,
            @ConfigProperty(
                    name = "recommendation.model-id",
                    defaultValue = "global.anthropic.claude-sonnet-5-5") String configuredModel,
            GuardrailTelemetry telemetry) {
        this.assistant = assistant;
        if (configuredModel == null || configuredModel.isBlank()) {
            throw new IllegalArgumentException("configuredModel must not be blank");
        }
        this.configuredModel = configuredModel;
        this.telemetry = telemetry;
    }

    RecommendationAgentService(RecommendationAssistant assistant, String configuredModel) {
        this(assistant, configuredModel, null);
    }

    @Override
    @WithSpan("decision_inference.recommendation")
    public RecommendationResponse recommend(String userRequest) {
        long started = System.nanoTime();
        Result<String> result;
        try {
            result = assistant.recommend(userRequest);
        } catch (RuntimeException exception) {
            if (telemetry != null) {
                telemetry.recordRecommendationFailure(exception);
            }
            throw exception;
        }
        Duration latency = Duration.ofNanos(System.nanoTime() - started);
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new IllegalStateException("Bedrock returned an empty recommendation");
        }
        if (result.tokenUsage() == null
                || result.tokenUsage().inputTokenCount() == null
                || result.tokenUsage().outputTokenCount() == null) {
            throw new IllegalStateException("Bedrock recommendation did not include token usage");
        }

        ChatResponse finalResponse = result.finalResponse();
        String model = finalResponse != null
                && finalResponse.modelName() != null
                && !finalResponse.modelName().isBlank()
                        ? finalResponse.modelName()
                        : configuredModel;
        RecommendationResponse response = new RecommendationResponse(
                result.content(),
                model,
                new TokenUsage(
                        result.tokenUsage().inputTokenCount(),
                        result.tokenUsage().outputTokenCount()),
                latency);
        if (telemetry != null) {
            telemetry.recordRecommendation(response);
        }
        return response;
    }
}
