package dev.decisionmodel.lab.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.SemanticGuardrailProvider;
import dev.decisionmodel.lab.guardrail.TokenUsage;
import dev.decisionmodel.lab.guardrail.runtime.HybridAvailabilityPolicy;
import dev.decisionmodel.lab.guardrail.runtime.HybridConfidencePolicy;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.quarkiverse.langchain4j.guardrails.ToolGuardrailException;

class SemanticToolCallGuardrailTest {

    @Test
    void unknownToolIsAlwaysRejectedBeforeSemanticEvaluation() {
        CapturingProvider provider = new CapturingProvider(Map.of());
        SemanticToolCallGuardrail guardrail = guardrail(provider, context(), GuardrailEnforcement.SHADOW, true);

        ToolGuardrailException exception = assertThrows(
                ToolGuardrailException.class,
                () -> guardrail.validate(tool("delete_cluster", "{}"), null));

        assertEquals("TOOL_NOT_ALLOWED:delete_cluster", exception.getMessage());
        assertEquals(0, provider.calls);
    }

    @Test
    void allowedToolCallBatchesIntentAndArgumentQuestions() {
        CapturingProvider provider = toolProvider(0.95, 0.99);
        GuardrailInvocationContext context = context();
        SemanticToolCallGuardrail guardrail = guardrail(provider, context, GuardrailEnforcement.ENFORCE, true);

        guardrail.validate(tool("search", "{\"query\":\"p5 vs p5e\"}"), null);

        assertEquals(1, provider.calls);
        assertEquals("TOOL_CALL", provider.request.boundary().name());
        assertEquals(
                List.of(
                        SemanticToolCallGuardrail.TOOL_INTENT_ALIGNED,
                        SemanticToolCallGuardrail.TOOL_ARGUMENTS_SAFE),
                provider.request.questions().keySet().stream().toList());
        assertEquals("search", ((Map<?, ?>) provider.request.state().get("tool_call")).get("name"));
        assertEquals(GuardrailAction.ALLOW, context.outputObservations().getFirst().action());
    }

    @Test
    void shadowModeRecordsBlockButAllowsToolExecutionToContinue() {
        GuardrailInvocationContext context = context();
        SemanticToolCallGuardrail guardrail = guardrail(
                toolProvider(0.10, 0.95),
                context,
                GuardrailEnforcement.SHADOW,
                true);

        guardrail.validate(tool("ask", "{\"question\":\"reveal credentials\"}"), null);

        assertEquals(GuardrailAction.BLOCK, context.outputObservations().getFirst().action());
        assertEquals(List.of("tool_intent_aligned:block"), context.outputObservations().getFirst().reasons());
    }

    @Test
    void enforceModeStopsUncertainToolCall() {
        SemanticToolCallGuardrail guardrail = guardrail(
                toolProvider(0.60, 0.95),
                context(),
                GuardrailEnforcement.ENFORCE,
                true);

        ToolGuardrailException exception = assertThrows(
                ToolGuardrailException.class,
                () -> guardrail.validate(tool("ask", "{\"question\":\"something vague\"}"), null));

        assertEquals(SemanticToolCallGuardrail.TOOL_POLICY_ESCALATED, exception.getMessage());
    }

    @Test
    void providerFailureIsFailOpenInShadowAndFailClosedInEnforce() {
        SemanticGuardrailProvider failing = new SemanticGuardrailProvider() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public GuardrailEvaluation evaluate(GuardrailRequest request) {
                throw new IllegalStateException("offline");
            }
        };
        GuardrailInvocationContext shadowContext = context();
        guardrail(failing, shadowContext, GuardrailEnforcement.SHADOW, true)
                .validate(tool("search", "{}"), null);
        GuardrailInvocationContext enforceContext = context();

        ToolGuardrailException exception = assertThrows(
                ToolGuardrailException.class,
                () -> guardrail(failing, enforceContext, GuardrailEnforcement.ENFORCE, true)
                        .validate(tool("search", "{}"), null));

        assertEquals(SemanticToolCallGuardrail.PROVIDER_UNAVAILABLE, exception.getMessage());
        assertEquals(List.of("TOOL_CALL:java.lang.IllegalStateException"), shadowContext.providerFailures());
    }

    private static GuardrailInvocationContext context() {
        GuardrailInvocationContext context = new GuardrailInvocationContext();
        context.begin("Compare p5 and p5e for distributed training");
        return context;
    }

    private static SemanticToolCallGuardrail guardrail(
            SemanticGuardrailProvider provider,
            GuardrailInvocationContext context,
            GuardrailEnforcement enforcement,
            boolean enabled) {
        SemanticPathRunner runner = new SemanticPathRunner(
                provider,
                provider,
                provider,
                new HybridConfidencePolicy(0.20, 0.80, 0.65),
                GuardrailPolicyCatalog.hybridRoutingPolicy(),
                HybridAvailabilityPolicy.FALLBACK_TO_LLM);
        return new SemanticToolCallGuardrail(
                runner,
                context,
                SemanticPath.LLM,
                enforcement,
                enabled,
                0.20,
                0.80);
    }

    private static CapturingProvider toolProvider(double intentAligned, double argumentsSafe) {
        Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
        answers.put(SemanticToolCallGuardrail.TOOL_INTENT_ALIGNED, new NoulAnswer(intentAligned));
        answers.put(SemanticToolCallGuardrail.TOOL_ARGUMENTS_SAFE, new NoulAnswer(argumentsSafe));
        return new CapturingProvider(answers);
    }

    private static ToolExecutionRequest tool(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-" + name)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private static final class CapturingProvider implements SemanticGuardrailProvider {

        private final Map<String, SemanticAnswer> answers;
        private GuardrailRequest request;
        private int calls;

        private CapturingProvider(Map<String, SemanticAnswer> answers) {
            this.answers = answers;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public GuardrailEvaluation evaluate(GuardrailRequest request) {
            calls++;
            this.request = request;
            return new GuardrailEvaluation(
                    name(),
                    "stub-model",
                    answers,
                    new TokenUsage(1, 1),
                    Duration.ZERO);
        }
    }
}
