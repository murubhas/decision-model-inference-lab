package dev.decisionmodel.lab.a2a;

import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GuardrailExperimentWorkflow {

    private static final Logger LOG = Logger.getLogger(GuardrailExperimentWorkflow.class);

    static final String READY_RESPONSE = "A2A request accepted. The JSON-RPC transport, Agent Card, "
            + "provider-neutral guardrail runtime, Bedrock control provider, and Jev adapter are active. "
            + "Recommendation generation will be enabled after the evidence-tool workflow is connected.";

    static final String EMPTY_RESPONSE = "A text request is required for the accelerated-compute comparison.";

    static final String BLOCKED_RESPONSE = "Request blocked by the semantic input policy.";
    static final String ESCALATED_RESPONSE = "Request requires review before the agent can continue.";
    static final String UNAVAILABLE_RESPONSE = "Request stopped because a required semantic guardrail is unavailable.";
    static final String TOOL_BLOCKED_RESPONSE = "A proposed evidence-tool call was blocked by semantic policy.";
    static final String OUTPUT_BLOCKED_RESPONSE = "The generated recommendation was withheld by semantic output policy.";
    static final String RECOMMENDATION_UNAVAILABLE_RESPONSE = "Recommendation generation is temporarily unavailable.";

    private final InputGuardrailEvaluator inputGuardrail;
    private final RecommendationGateway recommendationGateway;
    private final GuardrailEnforcement enforcement;
    private final boolean semanticGuardrailEnabled;
    private final boolean recommendationEnabled;

    @Inject
    public GuardrailExperimentWorkflow(
            InputGuardrailEvaluator inputGuardrail,
            RecommendationGateway recommendationGateway,
            @ConfigProperty(name = "guardrail.enforcement", defaultValue = "SHADOW") GuardrailEnforcement enforcement,
            @ConfigProperty(name = "guardrail.semantic.enabled", defaultValue = "true") boolean semanticGuardrailEnabled,
            @ConfigProperty(name = "recommendation.enabled", defaultValue = "true") boolean recommendationEnabled) {
        this.inputGuardrail = inputGuardrail;
        this.recommendationGateway = recommendationGateway;
        this.enforcement = enforcement;
        this.semanticGuardrailEnabled = semanticGuardrailEnabled;
        this.recommendationEnabled = recommendationEnabled;
    }

    public String handle(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return EMPTY_RESPONSE;
        }
        if (recommendationEnabled) {
            return generateRecommendation(userInput);
        }
        if (!semanticGuardrailEnabled) {
            return READY_RESPONSE;
        }

        InputGuardrailResult result;
        String correlationId = UUID.randomUUID().toString();
        try {
            result = inputGuardrail.evaluate(correlationId, userInput);
        } catch (RuntimeException exception) {
            LOG.warnf(
                    "Semantic input guardrail failed for correlationId=%s failureType=%s",
                    correlationId,
                    exception.getClass().getName());
            LOG.debug("Semantic input guardrail failure details", exception);
            return enforcement == GuardrailEnforcement.SHADOW
                    ? READY_RESPONSE + " Semantic input guardrail was unavailable in SHADOW mode."
                    : UNAVAILABLE_RESPONSE;
        }

        if (enforcement == GuardrailEnforcement.ENFORCE) {
            if (result.action() == GuardrailAction.BLOCK) {
                return BLOCKED_RESPONSE;
            }
            if (result.action() == GuardrailAction.ESCALATE) {
                return ESCALATED_RESPONSE;
            }
        }

        return READY_RESPONSE + " Semantic input guardrail: mode=" + enforcement
                + ", path=" + result.pathResult().path()
                + ", observedAction=" + result.action()
                + ", selectedProvider=" + result.pathResult().selectedEvaluation().provider() + ".";
    }

    private String generateRecommendation(String userInput) {
        String correlationId = UUID.randomUUID().toString();
        try {
            RecommendationResponse response = recommendationGateway.recommend(userInput);
            LOG.infof(
                    "Recommendation completed correlationId=%s model=%s inputTokens=%d outputTokens=%d latencyMs=%d",
                    correlationId,
                    response.model(),
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.latency().toMillis());
            return response.text();
        } catch (RuntimeException exception) {
            String response = mapFailure(exception);
            LOG.warnf(
                    "Recommendation failed correlationId=%s failureType=%s responseCode=%s",
                    correlationId,
                    exception.getClass().getName(),
                    responseCode(response));
            LOG.debug("Recommendation failure details", exception);
            return response;
        }
    }

    private static String mapFailure(RuntimeException exception) {
        if (contains(exception, QuarkusSemanticInputGuardrail.BLOCKED)) {
            return BLOCKED_RESPONSE;
        }
        if (contains(exception, QuarkusSemanticInputGuardrail.ESCALATED)
                || contains(exception, SemanticToolCallGuardrail.TOOL_POLICY_ESCALATED)
                || contains(exception, SemanticModelOutputGuardrail.OUTPUT_POLICY_ESCALATED)) {
            return ESCALATED_RESPONSE;
        }
        if (contains(exception, SemanticToolCallGuardrail.TOOL_NOT_ALLOWED)
                || contains(exception, SemanticToolCallGuardrail.TOOL_POLICY_BLOCKED)) {
            return TOOL_BLOCKED_RESPONSE;
        }
        if (contains(exception, SemanticModelOutputGuardrail.OUTPUT_POLICY_BLOCKED)) {
            return OUTPUT_BLOCKED_RESPONSE;
        }
        if (contains(exception, QuarkusSemanticInputGuardrail.UNAVAILABLE)
                || contains(exception, SemanticToolCallGuardrail.PROVIDER_UNAVAILABLE)
                || contains(exception, SemanticModelOutputGuardrail.PROVIDER_UNAVAILABLE)) {
            return UNAVAILABLE_RESPONSE;
        }
        return RECOMMENDATION_UNAVAILABLE_RESPONSE;
    }

    private static boolean contains(Throwable exception, String code) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current.getMessage() != null && current.getMessage().contains(code)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String responseCode(String response) {
        if (BLOCKED_RESPONSE.equals(response)) {
            return "INPUT_BLOCKED";
        }
        if (ESCALATED_RESPONSE.equals(response)) {
            return "REVIEW_REQUIRED";
        }
        if (TOOL_BLOCKED_RESPONSE.equals(response)) {
            return "TOOL_BLOCKED";
        }
        if (OUTPUT_BLOCKED_RESPONSE.equals(response)) {
            return "OUTPUT_BLOCKED";
        }
        if (UNAVAILABLE_RESPONSE.equals(response)) {
            return "GUARDRAIL_UNAVAILABLE";
        }
        return "GENERATION_UNAVAILABLE";
    }
}
