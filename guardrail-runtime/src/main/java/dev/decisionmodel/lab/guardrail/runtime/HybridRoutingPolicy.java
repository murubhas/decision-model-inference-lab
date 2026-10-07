package dev.decisionmodel.lab.guardrail.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.SemanticQuestion;

public final class HybridRoutingPolicy {

    private final Map<String, HybridRoutingRule> rules;

    public HybridRoutingPolicy(Map<String, HybridRoutingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
        LinkedHashMap<String, HybridRoutingRule> copy = new LinkedHashMap<>();
        rules.forEach((questionId, rule) -> {
            if (questionId == null || questionId.isBlank()) {
                throw new IllegalArgumentException("rules must not contain a blank question id");
            }
            if (rule == null) {
                throw new IllegalArgumentException("rules must not contain null values");
            }
            copy.put(questionId, rule);
        });
        this.rules = Collections.unmodifiableMap(copy);
    }

    public Map<String, HybridRoutingRule> rules() {
        return rules;
    }

    public List<String> questionIds(GuardrailRequest request, HybridQuestionRoute route) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        validate(request);
        return request.questions().keySet().stream()
                .filter(questionId -> rules.get(questionId).route() == route)
                .toList();
    }

    public GuardrailRequest project(GuardrailRequest request, Collection<String> questionIds) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        validate(request);
        Set<String> selectedIds = selectedIds(questionIds);

        LinkedHashMap<String, SemanticQuestion> projectedQuestions = new LinkedHashMap<>();
        request.questions().forEach((questionId, question) -> {
            if (selectedIds.contains(questionId)) {
                projectedQuestions.put(questionId, question);
            }
        });
        if (projectedQuestions.size() != selectedIds.size()) {
            LinkedHashSet<String> unknown = new LinkedHashSet<>(selectedIds);
            unknown.removeAll(projectedQuestions.keySet());
            throw new IllegalArgumentException("question ids are not present in request: " + unknown);
        }

        LinkedHashSet<String> requiredKeys = new LinkedHashSet<>();
        projectedQuestions.keySet().forEach(questionId ->
                requiredKeys.addAll(rules.get(questionId).requiredStateKeys()));
        LinkedHashMap<String, Object> projectedState = new LinkedHashMap<>();
        request.state().forEach((key, value) -> {
            if (requiredKeys.contains(key)) {
                projectedState.put(key, value);
            }
        });

        return new GuardrailRequest(
                request.correlationId(),
                request.boundary(),
                projectedState,
                projectedQuestions);
    }

    public void validate(GuardrailRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        for (String questionId : request.questions().keySet()) {
            HybridRoutingRule rule = rules.get(questionId);
            if (rule == null) {
                throw new IllegalArgumentException("no hybrid routing rule for question '" + questionId + "'");
            }
            LinkedHashSet<String> missing = new LinkedHashSet<>(rule.requiredStateKeys());
            missing.removeAll(request.state().keySet());
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "question '" + questionId + "' is missing required state keys " + missing);
            }
        }
    }

    private static Set<String> selectedIds(Collection<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            throw new IllegalArgumentException("questionIds must not be empty");
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String questionId : questionIds) {
            if (questionId == null || questionId.isBlank()) {
                throw new IllegalArgumentException("questionIds must not contain blank values");
            }
            if (!selected.add(questionId)) {
                throw new IllegalArgumentException("questionIds must not contain duplicates");
            }
        }
        return selected;
    }
}
