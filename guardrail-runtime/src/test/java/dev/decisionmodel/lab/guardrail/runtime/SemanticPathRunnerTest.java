package dev.decisionmodel.lab.guardrail.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import dev.decisionmodel.lab.guardrail.ChoiceAnswer;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.NoulQuestion;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.SemanticGuardrailProvider;
import dev.decisionmodel.lab.guardrail.SemanticQuestion;
import dev.decisionmodel.lab.guardrail.TokenUsage;

class SemanticPathRunnerTest {

    private static final GuardrailRequest REQUEST = new GuardrailRequest(
            "case-001",
            GuardrailBoundary.INPUT,
            Map.of("message", "Compare p5 and p5e"),
            Map.of("risk", new NoulQuestion("Is `message` risky?")));

    private static final HybridConfidencePolicy POLICY = new HybridConfidencePolicy(0.2, 0.8, 0.7);
    private static final HybridRoutingPolicy JEV_ONLY_ROUTING = routing(
            Map.of("risk", rule(HybridQuestionRoute.JEV_FIRST, "message")));

    @Test
    void directPathsCallOnlyTheirSelectedProvider() {
        StubProvider llm = provider("llm", ignored -> new NoulAnswer(0.1));
        StubProvider jev = provider("jev", ignored -> new NoulAnswer(0.1));
        StubProvider decider = provider("strands-decider", ignored -> new NoulAnswer(0.1));
        SemanticPathRunner runner = runner(
                llm,
                jev,
                decider,
                JEV_ONLY_ROUTING,
                HybridAvailabilityPolicy.PROPAGATE_FAILURE);

        SemanticPathResult llmResult = runner.run(SemanticPath.LLM, REQUEST);
        SemanticPathResult jevResult = runner.run(SemanticPath.JEV, REQUEST);
        SemanticPathResult deciderResult = runner.run(SemanticPath.DECIDER, REQUEST);

        assertEquals("llm", llmResult.selectedEvaluation().provider());
        assertEquals("jev", jevResult.selectedEvaluation().provider());
        assertEquals("strands-decider", deciderResult.selectedEvaluation().provider());
        assertEquals(1, llm.calls());
        assertEquals(1, jev.calls());
        assertEquals(1, decider.calls());
    }

    @Test
    void hybridKeepsClearJevAnswerOnTheFastPath() {
        StubProvider llm = provider("llm", ignored -> new NoulAnswer(0.1));
        StubProvider jev = provider("jev", ignored -> new NoulAnswer(0.95));
        SemanticPathRunner runner = runner(llm, jev, JEV_ONLY_ROUTING, HybridAvailabilityPolicy.PROPAGATE_FAILURE);

        SemanticPathResult result = runner.run(SemanticPath.HYBRID, REQUEST);

        assertEquals("jev", result.selectedEvaluation().provider());
        assertEquals(HybridFallbackReason.JEV_CLEAR, result.hybridAssessment().orElseThrow().reason());
        assertTrue(result.hybridAssessment().orElseThrow().llmQuestionIds().isEmpty());
        assertTrue(result.contributes(0));
        assertEquals(0, llm.calls());
        assertEquals(1, jev.calls());
    }

    @Test
    void hybridDeciderUsesDistinctProviderAndFallbackReasons() {
        StubProvider llm = provider("llm", ignored -> new NoulAnswer(0.1));
        StubProvider jev = provider("jev", ignored -> new NoulAnswer(0.95));
        StubProvider decider = provider("strands-decider", ignored -> new NoulAnswer(0.55));

        SemanticPathResult result = runner(
                llm,
                jev,
                decider,
                JEV_ONLY_ROUTING,
                HybridAvailabilityPolicy.PROPAGATE_FAILURE)
                .run(SemanticPath.HYBRID_DECIDER, REQUEST);

        assertEquals(SemanticPath.HYBRID_DECIDER, result.path());
        assertEquals("llm", result.selectedEvaluation().provider());
        assertEquals(HybridFallbackReason.DECIDER_UNCERTAIN,
                result.hybridAssessment().orElseThrow().reason());
        assertEquals(List.of("risk"),
                result.hybridAssessment().orElseThrow().uncertainQuestionIds());
        assertEquals(1, decider.calls());
        assertEquals(1, llm.calls());
        assertEquals(0, jev.calls());
    }

    @Test
    void hybridRoutesByQuestionAndProjectsProviderState() {
        GuardrailRequest request = mixedRequest("risk", "scope");
        HybridRoutingPolicy routing = routing(Map.of(
                "risk", rule(HybridQuestionRoute.JEV_FIRST, "message"),
                "scope", rule(HybridQuestionRoute.LLM_DIRECT, "message", "agent_scope")));
        StubProvider llm = provider("llm", id -> new NoulAnswer(id.equals("scope") ? 0.9 : 0.1));
        StubProvider jev = provider("jev", ignored -> new NoulAnswer(0.95));

        SemanticPathResult result = runner(llm, jev, routing, HybridAvailabilityPolicy.PROPAGATE_FAILURE)
                .run(SemanticPath.HYBRID, request);

        assertEquals("hybrid", result.selectedEvaluation().provider());
        assertEquals(List.of("risk", "scope"), List.copyOf(result.selectedEvaluation().answers().keySet()));
        assertEquals(List.of("risk"), List.copyOf(jev.lastRequest().questions().keySet()));
        assertEquals(List.of("message"), List.copyOf(jev.lastRequest().state().keySet()));
        assertEquals(List.of("scope"), List.copyOf(llm.lastRequest().questions().keySet()));
        assertEquals(List.of("message", "agent_scope"), List.copyOf(llm.lastRequest().state().keySet()));
        assertEquals(List.of("scope"), result.hybridAssessment().orElseThrow().llmQuestionIds());
        assertFalse(result.hybridAssessment().orElseThrow().fallbackRequired());
        assertTrue(result.contributes(0));
        assertTrue(result.contributes(1));
    }

    @Test
    void hybridBatchesDirectAndUncertainQuestionsIntoOneLlmCall() {
        GuardrailRequest request = mixedRequest("risk", "scope", "leak");
        HybridRoutingPolicy routing = routing(Map.of(
                "risk", rule(HybridQuestionRoute.JEV_FIRST, "message"),
                "scope", rule(HybridQuestionRoute.LLM_DIRECT, "message", "agent_scope"),
                "leak", rule(HybridQuestionRoute.JEV_FIRST, "message")));
        StubProvider llm = provider("llm", id -> new NoulAnswer(id.equals("scope") ? 0.9 : 0.1));
        StubProvider jev = provider("jev", id -> new NoulAnswer(id.equals("risk") ? 0.55 : 0.05));

        SemanticPathResult result = runner(llm, jev, routing, HybridAvailabilityPolicy.PROPAGATE_FAILURE)
                .run(SemanticPath.HYBRID, request);

        assertEquals(1, llm.calls());
        assertEquals(List.of("risk", "scope"), List.copyOf(llm.lastRequest().questions().keySet()));
        assertEquals(List.of("risk", "scope", "leak"),
                List.copyOf(result.selectedEvaluation().answers().keySet()));
        assertEquals(new NoulAnswer(0.1), result.selectedEvaluation().answers().get("risk"));
        assertEquals(new NoulAnswer(0.05), result.selectedEvaluation().answers().get("leak"));
        assertEquals(HybridFallbackReason.JEV_UNCERTAIN, result.hybridAssessment().orElseThrow().reason());
        assertEquals(List.of("risk"), result.hybridAssessment().orElseThrow().uncertainQuestionIds());
        assertEquals(List.of("risk", "scope"), result.hybridAssessment().orElseThrow().llmQuestionIds());
    }

    @Test
    void hybridUsesDistributionConfidenceForChoiceAndScoreAnswers() {
        GuardrailEvaluation lowConfidence = evaluation(
                "jev",
                Map.of("route", new ChoiceAnswer(
                        "compute", Map.of("compute", 0.55, "other", 0.45), 0.2)));

        HybridAssessment assessment = POLICY.assess(lowConfidence);

        assertEquals(HybridFallbackReason.JEV_UNCERTAIN, assessment.reason());
        assertEquals(List.of("route"), assessment.uncertainQuestionIds());
    }

    @Test
    void availabilityBehaviorIsExplicitPerRunner() {
        RuntimeException outage = new RuntimeException("jev unavailable");
        StubProvider failingJev = new StubProvider("jev", null, outage);
        StubProvider llm = provider("llm", ignored -> new NoulAnswer(0.1));

        RuntimeException propagated = assertThrows(RuntimeException.class,
                () -> runner(llm, failingJev, JEV_ONLY_ROUTING, HybridAvailabilityPolicy.PROPAGATE_FAILURE)
                        .run(SemanticPath.HYBRID, REQUEST));
        assertSame(outage, propagated);

        SemanticPathResult fallback = runner(
                llm,
                failingJev,
                JEV_ONLY_ROUTING,
                HybridAvailabilityPolicy.FALLBACK_TO_LLM)
                .run(SemanticPath.HYBRID, REQUEST);
        assertEquals("llm", fallback.selectedEvaluation().provider());
        assertEquals(HybridFallbackReason.JEV_UNAVAILABLE,
                fallback.hybridAssessment().orElseThrow().reason());
        assertEquals(List.of("risk"), fallback.hybridAssessment().orElseThrow().llmQuestionIds());
    }

    @Test
    void hybridRejectsUnknownQuestionsBeforeCallingAProvider() {
        StubProvider llm = provider("llm", ignored -> new NoulAnswer(0.1));
        StubProvider jev = provider("jev", ignored -> new NoulAnswer(0.1));
        HybridRoutingPolicy routing = routing(Map.of(
                "another_question", rule(HybridQuestionRoute.JEV_FIRST, "message")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> runner(llm, jev, routing, HybridAvailabilityPolicy.PROPAGATE_FAILURE)
                        .run(SemanticPath.HYBRID, REQUEST));

        assertTrue(exception.getMessage().contains("no hybrid routing rule"));
        assertEquals(0, llm.calls());
        assertEquals(0, jev.calls());
    }

    @Test
    void hybridRejectsMissingProjectedStateBeforeCallingAProvider() {
        StubProvider llm = provider("llm", ignored -> new NoulAnswer(0.1));
        StubProvider jev = provider("jev", ignored -> new NoulAnswer(0.1));
        HybridRoutingPolicy routing = routing(Map.of(
                "risk", rule(HybridQuestionRoute.JEV_FIRST, "missing_state")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> runner(llm, jev, routing, HybridAvailabilityPolicy.PROPAGATE_FAILURE)
                        .run(SemanticPath.HYBRID, REQUEST));

        assertTrue(exception.getMessage().contains("missing required state keys"));
        assertEquals(0, llm.calls());
        assertEquals(0, jev.calls());
    }

    private static SemanticPathRunner runner(
            SemanticGuardrailProvider llm,
            SemanticGuardrailProvider jev,
            HybridRoutingPolicy routing,
            HybridAvailabilityPolicy availabilityPolicy) {
        return runner(llm, jev, jev, routing, availabilityPolicy);
    }

    private static SemanticPathRunner runner(
            SemanticGuardrailProvider llm,
            SemanticGuardrailProvider jev,
            SemanticGuardrailProvider decider,
            HybridRoutingPolicy routing,
            HybridAvailabilityPolicy availabilityPolicy) {
        return new SemanticPathRunner(
                llm,
                jev,
                decider,
                POLICY,
                routing,
                availabilityPolicy);
    }

    private static GuardrailRequest mixedRequest(String... questionIds) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", "Compare p5 and p5e");
        state.put("agent_scope", "Accelerated compute advice");
        state.put("unrelated", "must not leave the process");
        Map<String, SemanticQuestion> questions = new LinkedHashMap<>();
        for (String questionId : questionIds) {
            questions.put(questionId, new NoulQuestion("Evaluate `message` for " + questionId));
        }
        return new GuardrailRequest("case-mixed", GuardrailBoundary.INPUT, state, questions);
    }

    private static HybridRoutingPolicy routing(Map<String, HybridRoutingRule> rules) {
        return new HybridRoutingPolicy(rules);
    }

    private static HybridRoutingRule rule(HybridQuestionRoute route, String... stateKeys) {
        return new HybridRoutingRule(route, Set.of(stateKeys));
    }

    private static StubProvider provider(String name, Function<String, SemanticAnswer> answers) {
        return new StubProvider(name, answers, null);
    }

    private static GuardrailEvaluation evaluation(String provider, Map<String, SemanticAnswer> answers) {
        return new GuardrailEvaluation(
                provider,
                provider + "-model",
                answers,
                new TokenUsage(10, 2),
                Duration.ofMillis(5));
    }

    private static final class StubProvider implements SemanticGuardrailProvider {

        private final String name;
        private final Function<String, SemanticAnswer> answers;
        private final RuntimeException failure;
        private final List<GuardrailRequest> requests = new ArrayList<>();

        private StubProvider(
                String name,
                Function<String, SemanticAnswer> answers,
                RuntimeException failure) {
            this.name = name;
            this.answers = answers;
            this.failure = failure;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public GuardrailEvaluation evaluate(GuardrailRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            Map<String, SemanticAnswer> response = new LinkedHashMap<>();
            request.questions().keySet().forEach(id -> response.put(id, answers.apply(id)));
            return evaluation(name, response);
        }

        int calls() {
            return requests.size();
        }

        GuardrailRequest lastRequest() {
            return requests.getLast();
        }
    }
}
