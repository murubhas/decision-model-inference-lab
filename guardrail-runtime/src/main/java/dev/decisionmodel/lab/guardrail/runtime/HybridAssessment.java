package dev.decisionmodel.lab.guardrail.runtime;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public record HybridAssessment(
        HybridFallbackReason reason,
        List<String> uncertainQuestionIds,
        List<String> llmQuestionIds) {

    public HybridAssessment {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        uncertainQuestionIds = validatedIds(uncertainQuestionIds, "uncertainQuestionIds");
        llmQuestionIds = validatedIds(llmQuestionIds, "llmQuestionIds");
        if (reason.isUncertain() && uncertainQuestionIds.isEmpty()) {
            throw new IllegalArgumentException("uncertain assessments require at least one question id");
        }
        if (!reason.isUncertain() && !uncertainQuestionIds.isEmpty()) {
            throw new IllegalArgumentException("only uncertain assessments may include question ids");
        }
        if (!new LinkedHashSet<>(llmQuestionIds).containsAll(uncertainQuestionIds)) {
            throw new IllegalArgumentException("uncertainQuestionIds must be routed to the LLM");
        }
    }

    public boolean fallbackRequired() {
        return !reason.isClear();
    }

    public static HybridAssessment clear() {
        return clear(List.of());
    }

    public static HybridAssessment clear(List<String> llmQuestionIds) {
        return clear(HybridDecisionProvider.JEV, llmQuestionIds);
    }

    public static HybridAssessment clear(
            HybridDecisionProvider provider,
            List<String> llmQuestionIds) {
        return new HybridAssessment(provider.clearReason(), List.of(), llmQuestionIds);
    }

    public static HybridAssessment uncertain(List<String> questionIds) {
        return uncertain(questionIds, questionIds);
    }

    public static HybridAssessment uncertain(List<String> questionIds, List<String> llmQuestionIds) {
        return uncertain(HybridDecisionProvider.JEV, questionIds, llmQuestionIds);
    }

    public static HybridAssessment uncertain(
            HybridDecisionProvider provider,
            List<String> questionIds,
            List<String> llmQuestionIds) {
        return new HybridAssessment(provider.uncertainReason(), questionIds, llmQuestionIds);
    }

    public static HybridAssessment unavailable() {
        return unavailable(List.of());
    }

    public static HybridAssessment unavailable(List<String> llmQuestionIds) {
        return unavailable(HybridDecisionProvider.JEV, llmQuestionIds);
    }

    public static HybridAssessment unavailable(
            HybridDecisionProvider provider,
            List<String> llmQuestionIds) {
        return new HybridAssessment(provider.unavailableReason(), List.of(), llmQuestionIds);
    }

    private static List<String> validatedIds(List<String> ids, String field) {
        if (ids == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank values");
            }
            if (!unique.add(id)) {
                throw new IllegalArgumentException(field + " must not contain duplicates");
            }
        }
        return List.copyOf(unique);
    }
}
