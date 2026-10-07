package dev.decisionmodel.lab.guardrail.runtime;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.SemanticGuardrailProvider;
import dev.decisionmodel.lab.guardrail.TokenUsage;

public final class SemanticPathRunner {

    private final SemanticGuardrailProvider llmProvider;
    private final SemanticGuardrailProvider jevProvider;
    private final SemanticGuardrailProvider deciderProvider;
    private final HybridConfidencePolicy confidencePolicy;
    private final HybridRoutingPolicy routingPolicy;
    private final HybridAvailabilityPolicy availabilityPolicy;

    public SemanticPathRunner(
            SemanticGuardrailProvider llmProvider,
            SemanticGuardrailProvider jevProvider,
            SemanticGuardrailProvider deciderProvider,
            HybridConfidencePolicy confidencePolicy,
            HybridRoutingPolicy routingPolicy,
            HybridAvailabilityPolicy availabilityPolicy) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider must not be null");
        this.jevProvider = Objects.requireNonNull(jevProvider, "jevProvider must not be null");
        this.deciderProvider = Objects.requireNonNull(deciderProvider, "deciderProvider must not be null");
        this.confidencePolicy = Objects.requireNonNull(confidencePolicy, "confidencePolicy must not be null");
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy must not be null");
        this.availabilityPolicy = Objects.requireNonNull(availabilityPolicy, "availabilityPolicy must not be null");
    }

    public SemanticPathResult run(SemanticPath path, GuardrailRequest request) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(request, "request must not be null");

        return switch (path) {
            case LLM -> direct(path, llmProvider.evaluate(request));
            case JEV -> direct(path, jevProvider.evaluate(request));
            case HYBRID -> hybrid(path, request, jevProvider, HybridDecisionProvider.JEV);
            case DECIDER -> direct(path, deciderProvider.evaluate(request));
            case HYBRID_DECIDER -> hybrid(
                    path,
                    request,
                    deciderProvider,
                    HybridDecisionProvider.STRANDS_DECIDER);
        };
    }

    private SemanticPathResult hybrid(
            SemanticPath path,
            GuardrailRequest request,
            SemanticGuardrailProvider decisionProvider,
            HybridDecisionProvider providerType) {
        List<String> decisionQuestionIds = routingPolicy.questionIds(
                request, HybridQuestionRoute.JEV_FIRST);
        if (decisionQuestionIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "hybrid requests require at least one decision-model-first question");
        }
        List<String> directLlmQuestionIds = routingPolicy.questionIds(request, HybridQuestionRoute.LLM_DIRECT);
        GuardrailRequest decisionRequest = routingPolicy.project(request, decisionQuestionIds);

        GuardrailEvaluation decisionEvaluation;
        try {
            decisionEvaluation = evaluate(decisionProvider, decisionRequest);
        } catch (RuntimeException exception) {
            if (availabilityPolicy == HybridAvailabilityPolicy.PROPAGATE_FAILURE) {
                throw exception;
            }
            List<String> allQuestionIds = List.copyOf(request.questions().keySet());
            GuardrailEvaluation llmEvaluation = evaluate(
                    llmProvider,
                    routingPolicy.project(request, allQuestionIds));
            return new SemanticPathResult(
                    path,
                    List.of(llmEvaluation),
                    llmEvaluation,
                    List.of(0),
                    Optional.of(HybridAssessment.unavailable(providerType, allQuestionIds)));
        }

        HybridAssessment decisionAssessment = confidencePolicy.assess(
                decisionEvaluation, providerType);
        List<String> llmQuestionIds = mergeInRequestOrder(
                request,
                directLlmQuestionIds,
                decisionAssessment.uncertainQuestionIds());
        HybridAssessment assessment = decisionAssessment.fallbackRequired()
                ? HybridAssessment.uncertain(
                        providerType,
                        decisionAssessment.uncertainQuestionIds(),
                        llmQuestionIds)
                : HybridAssessment.clear(providerType, llmQuestionIds);
        if (llmQuestionIds.isEmpty()) {
            return new SemanticPathResult(
                    path,
                    List.of(decisionEvaluation),
                    decisionEvaluation,
                    List.of(0),
                    Optional.of(assessment));
        }

        GuardrailEvaluation llmEvaluation = evaluate(
                llmProvider,
                routingPolicy.project(request, llmQuestionIds));
        List<GuardrailEvaluation> evaluations = List.of(decisionEvaluation, llmEvaluation);
        Set<String> llmQuestions = Set.copyOf(llmQuestionIds);
        boolean decisionProviderContributes = request.questions().keySet().stream()
                .anyMatch(questionId -> !llmQuestions.contains(questionId));
        if (!decisionProviderContributes) {
            return new SemanticPathResult(
                    path,
                    evaluations,
                    llmEvaluation,
                    List.of(1),
                    Optional.of(assessment));
        }

        GuardrailEvaluation merged = merge(
                request, decisionEvaluation, llmEvaluation, llmQuestions);
        return new SemanticPathResult(
                path,
                evaluations,
                merged,
                List.of(0, 1),
                Optional.of(assessment));
    }

    private static SemanticPathResult direct(SemanticPath path, GuardrailEvaluation evaluation) {
        return new SemanticPathResult(
                path,
                List.of(evaluation),
                evaluation,
                List.of(0),
                Optional.empty());
    }

    private static GuardrailEvaluation evaluate(
            SemanticGuardrailProvider provider,
            GuardrailRequest request) {
        GuardrailEvaluation evaluation = provider.evaluate(request);
        if (!evaluation.answers().keySet().equals(request.questions().keySet())) {
            throw new IllegalStateException(
                    provider.name() + " answer ids do not match the requested question ids");
        }
        return evaluation;
    }

    private static List<String> mergeInRequestOrder(
            GuardrailRequest request,
            List<String> first,
            List<String> second) {
        LinkedHashSet<String> selected = new LinkedHashSet<>(first);
        selected.addAll(second);
        return request.questions().keySet().stream()
                .filter(selected::contains)
                .toList();
    }

    private static GuardrailEvaluation merge(
            GuardrailRequest request,
            GuardrailEvaluation decisionEvaluation,
            GuardrailEvaluation llmEvaluation,
            Set<String> llmQuestionIds) {
        Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
        request.questions().keySet().forEach(questionId -> answers.put(
                questionId,
                llmQuestionIds.contains(questionId)
                        ? llmEvaluation.answers().get(questionId)
                        : decisionEvaluation.answers().get(questionId)));
        TokenUsage usage = new TokenUsage(
                Math.addExact(
                        decisionEvaluation.usage().inputTokens(), llmEvaluation.usage().inputTokens()),
                Math.addExact(
                        decisionEvaluation.usage().outputTokens(), llmEvaluation.usage().outputTokens()));
        return new GuardrailEvaluation(
                "hybrid",
                decisionEvaluation.model() + "+" + llmEvaluation.model(),
                answers,
                usage,
                decisionEvaluation.latency().plus(llmEvaluation.latency()));
    }
}
