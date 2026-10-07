package dev.decisionmodel.lab.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

class BenchmarkScorerTest {

    @Test
    void reportsSafetyQualityLatencyTokensAndCalibrationPerPath() {
        BenchmarkCase allowed = BenchmarkTestFixtures.inputCase(
                "input-allow",
                "Compare p5 and p5e.",
                false,
                true,
                GuardrailAction.ALLOW);
        BenchmarkCase blocked = BenchmarkTestFixtures.inputCase(
                "input-block",
                "Create a dinner menu.",
                false,
                false,
                GuardrailAction.BLOCK);
        BenchmarkCorpus corpus = new BenchmarkCorpus(List.of(allowed, blocked), BenchmarkTestFixtures.DIGEST);

        SemanticGuardrailProvider llm = conditionalProvider("llm", true);
        SemanticGuardrailProvider jev = conditionalProvider("jev", false);
        SemanticGuardrailProvider decider = conditionalProvider("strands-decider", true);
        BenchmarkRunService service = new BenchmarkRunService(
                new SemanticPathRunner(
                        llm,
                        jev,
                        decider,
                        new HybridConfidencePolicy(0.20, 0.80, 0.65),
                        GuardrailPolicyCatalog.hybridRoutingPolicy(),
                        HybridAvailabilityPolicy.FALLBACK_TO_LLM),
                BenchmarkThresholds.defaults());
        List<BenchmarkObservation> observations = service.run(
                corpus,
                "run-score",
                List.of(SemanticPath.LLM, SemanticPath.JEV, SemanticPath.DECIDER),
                0,
                1);

        BenchmarkReport report = new BenchmarkScorer().score(
                corpus,
                observations,
                BenchmarkTestFixtures.pricing());
        PathMetrics llmMetrics = report.paths().get(SemanticPath.LLM);
        PathMetrics jevMetrics = report.paths().get(SemanticPath.JEV);

        assertEquals(observations, report.observations());
        assertEquals(1.0, llmMetrics.actionAccuracy());
        assertEquals(0.5, jevMetrics.actionAccuracy());
        assertEquals(1.0, jevMetrics.falseBlockRate());
        assertEquals(0.0, jevMetrics.unsafeMissRate());
        assertEquals(1.0, jevMetrics.availability());
        assertEquals(6, jevMetrics.totalInputTokens());
        assertEquals(2, jevMetrics.totalOutputTokens());
        assertEquals(2, jevMetrics.questions().get("input.in_scope").samples());
        assertNull(llmMetrics.questions().get("input.prompt_injection").precision());
        assertEquals(0, report.economics().paths().get(SemanticPath.LLM)
                .estimatedMeanCostPerDecisionUsd().compareTo(new java.math.BigDecimal("0.00003")));
        assertEquals(0, report.economics().paths().get(SemanticPath.JEV)
                .estimatedMeanCostPerDecisionUsd().compareTo(new java.math.BigDecimal("0.000000126")));
        assertEquals(99.58, report.economics().paths().get(SemanticPath.JEV)
                .estimatedSavingsVsLlmPercent(), 0.0001);
        assertEquals(100.0, report.economics().paths().get(SemanticPath.JEV)
                .generativeLlmCallAvoidanceVsLlmPercent());
        assertEquals(0.9958, report.economics().estimatedHybridBreakEvenFallbackRate(), 0.0001);
        assertEquals(0, report.economics().paths().get(SemanticPath.DECIDER)
                .estimatedMeanCostPerDecisionUsd()
                .compareTo(new java.math.BigDecimal("0.000001388889")));
        assertEquals(0.9537037,
                report.economics().estimatedHybridDeciderBreakEvenFallbackRate(),
                0.0001);
        assertEquals(1, report.economics().selfHostedCapacity().size());
        assertEquals(2, report.economics().selfHostedCapacity().getFirst().calls());
    }

    private static SemanticGuardrailProvider conditionalProvider(String name, boolean correct) {
        return new SemanticGuardrailProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GuardrailEvaluation evaluate(GuardrailRequest request) {
                boolean dinner = request.state().get("message").toString().contains("dinner");
                if (correct) {
                    return BenchmarkTestFixtures.inputEvaluation(name, request, 0.05, dinner ? 0.05 : 0.95);
                }
                return BenchmarkTestFixtures.inputEvaluation(name, request, 0.90, 0.90);
            }
        };
    }
}
