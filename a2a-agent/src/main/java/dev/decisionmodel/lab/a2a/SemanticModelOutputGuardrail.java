package dev.decisionmodel.lab.a2a;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.decisionmodel.lab.policy.NoulDecisionPolicy;
import dev.decisionmodel.lab.policy.PolicyDecision;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SemanticModelOutputGuardrail implements OutputGuardrail {

    static final String OUTPUT_POLICY_BLOCKED = "SEMANTIC_OUTPUT_BLOCKED";
    static final String OUTPUT_POLICY_ESCALATED = "SEMANTIC_OUTPUT_ESCALATED";
    static final String PROVIDER_UNAVAILABLE = "SEMANTIC_OUTPUT_GUARDRAIL_UNAVAILABLE";

    static final String RESPONSE_RELEVANT = GuardrailPolicyCatalog.RESPONSE_RELEVANT;
    static final String RESPONSE_GROUNDED = GuardrailPolicyCatalog.RESPONSE_GROUNDED;
    static final String SENSITIVE_EXPOSURE = GuardrailPolicyCatalog.SENSITIVE_EXPOSURE;
    static final String POLICY_LEAKAGE = GuardrailPolicyCatalog.POLICY_LEAKAGE;

    private final SemanticPathRunner runner;
    private final SemanticPath path;
    private final GuardrailInvocationContext context;
    private final GuardrailEnforcement enforcement;
    private final boolean enabled;
    private final NoulDecisionPolicy policy;

    @Inject
    public SemanticModelOutputGuardrail(
            SemanticPathRunner runner,
            GuardrailInvocationContext context,
            @ConfigProperty(name = "guardrail.semantic.mode", defaultValue = "HYBRID") SemanticPath path,
            @ConfigProperty(name = "guardrail.enforcement", defaultValue = "SHADOW") GuardrailEnforcement enforcement,
            @ConfigProperty(name = "guardrail.semantic.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(
                    name = "guardrail.output.risk-escalate-threshold",
                    defaultValue = "0.35") double riskEscalateThreshold,
            @ConfigProperty(
                    name = "guardrail.output.risk-block-threshold",
                    defaultValue = "0.80") double riskBlockThreshold,
            @ConfigProperty(
                    name = "guardrail.output.positive-block-threshold",
                    defaultValue = "0.20") double positiveBlockThreshold,
            @ConfigProperty(
                    name = "guardrail.output.positive-allow-threshold",
                    defaultValue = "0.80") double positiveAllowThreshold) {
        validateThresholds(
                riskEscalateThreshold,
                riskBlockThreshold,
                positiveBlockThreshold,
                positiveAllowThreshold);
        this.runner = runner;
        this.context = context;
        this.path = path;
        this.enforcement = enforcement;
        this.enabled = enabled;
        this.policy = GuardrailPolicyCatalog.outputPolicy(
                riskEscalateThreshold,
                riskBlockThreshold,
                positiveBlockThreshold,
                positiveAllowThreshold);
    }

    @Override
    @WithSpan("decision_inference.guardrail.output")
    public OutputGuardrailResult validate(AiMessage aiMessage) {
        if (aiMessage == null) {
            return fatal(OUTPUT_POLICY_BLOCKED);
        }
        if (aiMessage.hasToolExecutionRequests()) {
            return fatal(OUTPUT_POLICY_BLOCKED + ":tool-request-is-not-final-output");
        }
        return validateFinalOutput(aiMessage.text());
    }

    private OutputGuardrailResult validateFinalOutput(String response) {
        if (response == null || response.isBlank()) {
            return fatal(OUTPUT_POLICY_BLOCKED + ":empty");
        }
        if (!enabled) {
            return success();
        }

        return evaluate(
                GuardrailPolicyCatalog.outputRequest(
                        context.nextCorrelationId(GuardrailBoundary.OUTPUT),
                        requiredUserInput(),
                        response,
                        context.toolEvidenceState()),
                OUTPUT_POLICY_BLOCKED,
                OUTPUT_POLICY_ESCALATED);
    }

    private OutputGuardrailResult evaluate(
            GuardrailRequest request,
            String blockedCode,
            String escalatedCode) {
        SemanticPathResult pathResult;
        try {
            pathResult = runner.run(path, request);
        } catch (RuntimeException exception) {
            context.recordProviderFailure(request.boundary(), exception);
            return enforcement == GuardrailEnforcement.ENFORCE
                    ? fatal(PROVIDER_UNAVAILABLE, exception)
                    : success();
        }

        PolicyDecision decision = policy.decide(pathResult.selectedEvaluation());
        context.recordOutput(new SemanticGuardrailObservation(
                request.boundary(),
                decision.action(),
                decision.reasons(),
                pathResult));
        if (enforcement == GuardrailEnforcement.SHADOW || decision.action() == GuardrailAction.ALLOW) {
            return success();
        }
        return decision.action() == GuardrailAction.BLOCK ? fatal(blockedCode) : fatal(escalatedCode);
    }

    private String requiredUserInput() {
        String input = context.userInput();
        if (input == null || input.isBlank()) {
            throw new IllegalStateException("Input guardrail context is unavailable");
        }
        return input;
    }

    private static void validateThresholds(
            double riskEscalate,
            double riskBlock,
            double positiveBlock,
            double positiveAllow) {
        if (!probability(riskEscalate)
                || !probability(riskBlock)
                || !probability(positiveBlock)
                || !probability(positiveAllow)) {
            throw new IllegalArgumentException("semantic output thresholds must be between 0 and 1");
        }
        if (riskEscalate >= riskBlock || positiveBlock >= positiveAllow) {
            throw new IllegalArgumentException("semantic output thresholds are not ordered");
        }
    }

    private static boolean probability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
