package dev.decisionmodel.lab.a2a;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class QuarkusSemanticInputGuardrail implements InputGuardrail {

    static final String BLOCKED = "SEMANTIC_INPUT_BLOCKED";
    static final String ESCALATED = "SEMANTIC_INPUT_ESCALATED";
    static final String UNAVAILABLE = "SEMANTIC_INPUT_UNAVAILABLE";
    static final String UNSUPPORTED_CONTENT = "SEMANTIC_INPUT_REQUIRES_SINGLE_TEXT";

    private final InputGuardrailEvaluator evaluator;
    private final GuardrailInvocationContext context;
    private final GuardrailEnforcement enforcement;
    private final boolean enabled;

    @Inject
    public QuarkusSemanticInputGuardrail(
            InputGuardrailEvaluator evaluator,
            GuardrailInvocationContext context,
            @ConfigProperty(name = "guardrail.enforcement", defaultValue = "SHADOW") GuardrailEnforcement enforcement,
            @ConfigProperty(name = "guardrail.semantic.enabled", defaultValue = "true") boolean enabled) {
        this.evaluator = evaluator;
        this.context = context;
        this.enforcement = enforcement;
        this.enabled = enabled;
    }

    @Override
    @WithSpan("decision_inference.guardrail.input")
    public dev.langchain4j.guardrail.InputGuardrailResult validate(UserMessage userMessage) {
        if (userMessage == null || !userMessage.hasSingleText()) {
            return enforcement == GuardrailEnforcement.ENFORCE
                    ? fatal(UNSUPPORTED_CONTENT)
                    : success();
        }

        String text = userMessage.singleText();
        context.begin(text);
        if (!enabled) {
            return success();
        }

        InputGuardrailResult result;
        try {
            result = evaluator.evaluate(context.nextCorrelationId(GuardrailBoundary.INPUT), text);
        } catch (RuntimeException exception) {
            context.recordProviderFailure(GuardrailBoundary.INPUT, exception);
            return enforcement == GuardrailEnforcement.ENFORCE
                    ? fatal(UNAVAILABLE, exception)
                    : success();
        }
        context.recordInput(result);

        if (enforcement == GuardrailEnforcement.SHADOW || result.action() == GuardrailAction.ALLOW) {
            return success();
        }
        return result.action() == GuardrailAction.BLOCK ? fatal(BLOCKED) : fatal(ESCALATED);
    }
}
