package dev.decisionmodel.lab.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.SemanticGuardrailProvider;
import dev.decisionmodel.lab.guardrail.runtime.HybridAvailabilityPolicy;
import dev.decisionmodel.lab.guardrail.runtime.HybridConfidencePolicy;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import jakarta.enterprise.context.control.ActivateRequestContext;

class BenchmarkRunServiceTest {

    @Test
    void activatesRequestContextForStandaloneProviderCalls() throws NoSuchMethodException {
        assertTrue(BenchmarkRunService.class.getMethod(
                "run",
                BenchmarkCorpus.class,
                String.class,
                List.class,
                int.class,
                int.class).isAnnotationPresent(ActivateRequestContext.class));
    }

    @Test
    void recordsReadableInputsOutputsAndHybridFallbackMetrics() {
        BenchmarkCase benchmarkCase = BenchmarkTestFixtures.inputCase(
                "input-allow",
                "Compare p5 and p5e.",
                false,
                true,
                GuardrailAction.ALLOW);
        BenchmarkCorpus corpus = new BenchmarkCorpus(List.of(benchmarkCase), BenchmarkTestFixtures.DIGEST);
        BenchmarkRunService service = service(
                provider("llm", 0.05, 0.95),
                provider("jev", 0.50, 0.50));

        List<BenchmarkObservation> observations = service.run(
                corpus,
                "run-1",
                List.of(SemanticPath.LLM, SemanticPath.JEV, SemanticPath.HYBRID),
                1,
                2);

        assertEquals(9, observations.size());
        assertEquals(3, observations.stream().filter(item -> item.phase() == SamplePhase.WARMUP).count());
        assertEquals(6, observations.stream().filter(item -> item.phase() == SamplePhase.MEASURED).count());
        BenchmarkObservation hybrid = observations.stream()
                .filter(item -> item.path() == SemanticPath.HYBRID)
                .findFirst()
                .orElseThrow();
        assertTrue(hybrid.hybridFallback());
        assertEquals("JEV_UNCERTAIN", hybrid.hybridFallbackReason());
        assertEquals("llm", hybrid.selectedProvider());
        assertEquals(2, hybrid.providerCalls().size());
        assertEquals(8, hybrid.totalInputTokens());
        assertEquals(3, hybrid.totalOutputTokens());
        assertFalse(hybrid.answers().isEmpty());
        assertEquals("Compare p5 and p5e.", hybrid.input().get("userRequest"));
        ProviderCallObservation jevCall = hybrid.providerCalls().getFirst();
        ProviderCallObservation llmCall = hybrid.providerCalls().getLast();
        assertEquals(1, jevCall.semanticInput().questions().size());
        assertTrue(jevCall.semanticInput().questions().containsKey("prompt_injection"));
        assertEquals(jevCall.semanticInput().questions().keySet(), jevCall.output().keySet());
        assertEquals(2, llmCall.semanticInput().questions().size());
        assertTrue(llmCall.semanticInput().state().containsKey("agent_scope"));
        assertEquals(llmCall.semanticInput().questions().keySet(), llmCall.output().keySet());
    }

    @Test
    void convertsProviderFailureToAnEvidenceBearingErrorRecord() {
        SemanticGuardrailProvider failing = new SemanticGuardrailProvider() {
            @Override
            public String name() {
                return "llm";
            }

            @Override
            public GuardrailEvaluation evaluate(GuardrailRequest request) {
                throw new IllegalStateException("simulated provider failure");
            }
        };
        BenchmarkCase benchmarkCase = BenchmarkTestFixtures.inputCase(
                "input-allow",
                "Compare p5 and p5e.",
                false,
                true,
                GuardrailAction.ALLOW);
        BenchmarkRunService service = service(failing, provider("jev", 0.05, 0.95));

        BenchmarkObservation observation = service.run(
                new BenchmarkCorpus(List.of(benchmarkCase), BenchmarkTestFixtures.DIGEST),
                "run-2",
                List.of(SemanticPath.LLM),
                0,
                1).getFirst();

        assertEquals(ObservationStatus.ERROR, observation.status());
        assertEquals("java.lang.IllegalStateException", observation.errorType());
        assertEquals("Compare p5 and p5e.", observation.input().get("userRequest"));
        assertTrue(observation.answers().isEmpty());
        assertTrue(observation.providerCalls().isEmpty());
    }

    private static BenchmarkRunService service(
            SemanticGuardrailProvider llm,
            SemanticGuardrailProvider jev) {
        return new BenchmarkRunService(
                new SemanticPathRunner(
                        llm,
                        jev,
                        jev,
                        new HybridConfidencePolicy(0.20, 0.80, 0.65),
                        GuardrailPolicyCatalog.hybridRoutingPolicy(),
                        HybridAvailabilityPolicy.FALLBACK_TO_LLM),
                BenchmarkThresholds.defaults());
    }

    private static SemanticGuardrailProvider provider(
            String name,
            double injection,
            double inScope) {
        return new SemanticGuardrailProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GuardrailEvaluation evaluate(GuardrailRequest request) {
                return BenchmarkTestFixtures.inputEvaluation(name, request, injection, inScope);
            }
        };
    }
}
