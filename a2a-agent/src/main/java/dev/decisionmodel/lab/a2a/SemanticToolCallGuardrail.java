package dev.decisionmodel.lab.a2a;

import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.decisionmodel.lab.policy.NoulDecisionPolicy;
import dev.decisionmodel.lab.policy.PolicyDecision;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import io.quarkiverse.langchain4j.guardrails.ToolGuardrailException;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SemanticToolCallGuardrail implements ToolCallGuardrail {

    static final String TOOL_POLICY_BLOCKED = "SEMANTIC_TOOL_CALL_BLOCKED";
    static final String TOOL_POLICY_ESCALATED = "SEMANTIC_TOOL_CALL_ESCALATED";
    static final String PROVIDER_UNAVAILABLE = "SEMANTIC_TOOL_GUARDRAIL_UNAVAILABLE";
    static final String TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";

    static final String TOOL_INTENT_ALIGNED = GuardrailPolicyCatalog.TOOL_INTENT_ALIGNED;
    static final String TOOL_ARGUMENTS_SAFE = GuardrailPolicyCatalog.TOOL_ARGUMENTS_SAFE;

    private static final Set<String> ALLOWED_TOOLS = Set.of("search", "ask");

    private final SemanticPathRunner runner;
    private final SemanticPath path;
    private final GuardrailInvocationContext context;
    private final GuardrailEnforcement enforcement;
    private final boolean enabled;
    private final NoulDecisionPolicy policy;

    @Inject
    public SemanticToolCallGuardrail(
            SemanticPathRunner runner,
            GuardrailInvocationContext context,
            @ConfigProperty(name = "guardrail.semantic.mode", defaultValue = "HYBRID") SemanticPath path,
            @ConfigProperty(name = "guardrail.enforcement", defaultValue = "SHADOW") GuardrailEnforcement enforcement,
            @ConfigProperty(name = "guardrail.semantic.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(
                    name = "guardrail.tool.positive-block-threshold",
                    defaultValue = "0.20") double blockThreshold,
            @ConfigProperty(
                    name = "guardrail.tool.positive-allow-threshold",
                    defaultValue = "0.80") double allowThreshold) {
        if (!probability(blockThreshold) || !probability(allowThreshold) || blockThreshold >= allowThreshold) {
            throw new IllegalArgumentException("semantic tool thresholds are not ordered probabilities");
        }
        this.runner = runner;
        this.context = context;
        this.path = path;
        this.enforcement = enforcement;
        this.enabled = enabled;
        this.policy = GuardrailPolicyCatalog.toolCallPolicy(blockThreshold, allowThreshold);
    }

    static Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    @WithSpan("decision_inference.guardrail.tool-call")
    public void validate(ToolExecutionRequest request, InvocationContext invocationContext) {
        if (request == null || !ALLOWED_TOOLS.contains(request.name())) {
            String toolName = request == null ? "null" : request.name();
            throw fatal(TOOL_NOT_ALLOWED + ":" + toolName);
        }
        if (!enabled) {
            return;
        }

        SemanticPathResult pathResult;
        try {
            pathResult = runner.run(
                    path,
                    GuardrailPolicyCatalog.toolCallRequest(
                            context.nextCorrelationId(GuardrailBoundary.TOOL_CALL),
                            resolveUserInput(invocationContext),
                            request.name(),
                            request.arguments()));
        } catch (RuntimeException exception) {
            context.recordProviderFailure(GuardrailBoundary.TOOL_CALL, exception);
            if (enforcement == GuardrailEnforcement.ENFORCE) {
                throw fatal(PROVIDER_UNAVAILABLE, exception);
            }
            return;
        }

        PolicyDecision decision = policy.decide(pathResult.selectedEvaluation());
        context.recordOutput(new SemanticGuardrailObservation(
                GuardrailBoundary.TOOL_CALL,
                decision.action(),
                decision.reasons(),
                pathResult));
        if (enforcement == GuardrailEnforcement.SHADOW
                || decision.action() == GuardrailAction.ALLOW) {
            return;
        }
        throw fatal(decision.action() == GuardrailAction.BLOCK
                ? TOOL_POLICY_BLOCKED
                : TOOL_POLICY_ESCALATED);
    }

    private String resolveUserInput(InvocationContext invocationContext) {
        if (invocationContext != null) {
            UserMessage message = invocationContext.userMessage();
            if (message != null && message.hasSingleText()) {
                return message.singleText();
            }
        }
        String input = context.userInput();
        if (input == null || input.isBlank()) {
            throw new IllegalStateException("Input guardrail context is unavailable");
        }
        return input;
    }

    private static ToolGuardrailException fatal(String message) {
        return new ToolGuardrailException(message, true);
    }

    private static ToolGuardrailException fatal(String message, RuntimeException cause) {
        return new ToolGuardrailException(message, cause, true);
    }

    private static boolean probability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
