package dev.decisionmodel.lab.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;

class SemanticModelOutputGuardrailTest {

    @Test
    void rejectsToolRequestMessagesBecauseThisBoundaryOnlyAcceptsFinalOutput() {
        CapturingProvider provider = new CapturingProvider(Map.of());
        GuardrailInvocationContext context = context();
        SemanticModelOutputGuardrail guardrail = guardrail(provider, context, GuardrailEnforcement.SHADOW, true);

        OutputGuardrailResult result = guardrail.validate(AiMessage.from(tool("search", "{}")));

        assertTrue(result.isFatal());
        assertEquals(
                SemanticModelOutputGuardrail.OUTPUT_POLICY_BLOCKED + ":tool-request-is-not-final-output",
                result.failures().getFirst().message());
        assertEquals(0, provider.calls);
    }

    @Test
    void shadowModeRecordsSensitiveOutputButReturnsSuccess() {
        CapturingProvider provider = outputProvider(0.95, 0.95, 0.92, 0.05);
        GuardrailInvocationContext context = context();
        SemanticModelOutputGuardrail guardrail = guardrail(provider, context, GuardrailEnforcement.SHADOW, true);

        OutputGuardrailResult result = guardrail.validate(AiMessage.from("Use this secret token: example"));

        assertTrue(result.isSuccess());
        assertEquals(GuardrailAction.BLOCK, context.outputObservations().getFirst().action());
        assertEquals(List.of("sensitive_exposure:block"), context.outputObservations().getFirst().reasons());
    }

    @Test
    void enforceModeStopsUncertainFinalOutput() {
        CapturingProvider provider = outputProvider(0.60, 0.95, 0.05, 0.05);
        SemanticModelOutputGuardrail guardrail = guardrail(
                provider,
                context(),
                GuardrailEnforcement.ENFORCE,
                true);

        OutputGuardrailResult result = guardrail.validate(AiMessage.from("Perhaps use an accelerated instance."));

        assertTrue(result.isFatal());
        assertEquals(SemanticModelOutputGuardrail.OUTPUT_POLICY_ESCALATED, result.failures().getFirst().message());
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
        OutputGuardrailResult shadow = guardrail(failing, shadowContext, GuardrailEnforcement.SHADOW, true)
                .validate(AiMessage.from("Use p5e."));
        GuardrailInvocationContext enforceContext = context();
        OutputGuardrailResult enforce = guardrail(failing, enforceContext, GuardrailEnforcement.ENFORCE, true)
                .validate(AiMessage.from("Use p5e."));

        assertTrue(shadow.isSuccess());
        assertTrue(enforce.isFatal());
        assertEquals(SemanticModelOutputGuardrail.PROVIDER_UNAVAILABLE, enforce.failures().getFirst().message());
        assertEquals(List.of("OUTPUT:java.lang.IllegalStateException"), shadowContext.providerFailures());
    }

    @Test
    @SuppressWarnings("unchecked")
    void includesBoundedToolEvidenceInFinalOutputCheck() {
        CapturingProvider provider = outputProvider(0.95, 0.95, 0.05, 0.05);
        GuardrailInvocationContext context = context();
        context.recordToolEvidence("search", "p5e has H200 GPUs");

        guardrail(provider, context, GuardrailEnforcement.SHADOW, true)
                .validate(AiMessage.from("The retrieved specifications show that p5e uses H200 GPUs."));

        List<Map<String, String>> evidence =
                (List<Map<String, String>>) provider.request.state().get("tool_evidence");
        assertEquals("search", evidence.getFirst().get("tool"));
        assertEquals("p5e has H200 GPUs", evidence.getFirst().get("result"));
    }

    @Test
    void invalidThresholdOrderingIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticModelOutputGuardrail(
                runner(outputProvider(0.95, 0.95, 0.05, 0.05)),
                context(),
                SemanticPath.LLM,
                GuardrailEnforcement.SHADOW,
                true,
                0.80,
                0.35,
                0.20,
                0.80));
    }

    private static GuardrailInvocationContext context() {
        GuardrailInvocationContext context = new GuardrailInvocationContext();
        context.begin("Compare p5 and p5e for distributed training");
        return context;
    }

    private static SemanticModelOutputGuardrail guardrail(
            SemanticGuardrailProvider provider,
            GuardrailInvocationContext context,
            GuardrailEnforcement enforcement,
            boolean enabled) {
        return new SemanticModelOutputGuardrail(
                runner(provider),
                context,
                SemanticPath.LLM,
                enforcement,
                enabled,
                0.35,
                0.80,
                0.20,
                0.80);
    }

    private static SemanticPathRunner runner(SemanticGuardrailProvider provider) {
        return new SemanticPathRunner(
                provider,
                provider,
                provider,
                new HybridConfidencePolicy(0.20, 0.80, 0.65),
                GuardrailPolicyCatalog.hybridRoutingPolicy(),
                HybridAvailabilityPolicy.FALLBACK_TO_LLM);
    }

    private static CapturingProvider outputProvider(
            double relevant,
            double grounded,
            double sensitive,
            double leakage) {
        Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
        answers.put(SemanticModelOutputGuardrail.RESPONSE_RELEVANT, new NoulAnswer(relevant));
        answers.put(SemanticModelOutputGuardrail.RESPONSE_GROUNDED, new NoulAnswer(grounded));
        answers.put(SemanticModelOutputGuardrail.SENSITIVE_EXPOSURE, new NoulAnswer(sensitive));
        answers.put(SemanticModelOutputGuardrail.POLICY_LEAKAGE, new NoulAnswer(leakage));
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
