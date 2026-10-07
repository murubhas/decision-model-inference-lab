package dev.decisionmodel.lab.a2a;

import java.util.List;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;

public record SemanticGuardrailObservation(
        GuardrailBoundary boundary,
        GuardrailAction action,
        List<String> reasons,
        SemanticPathResult pathResult) {

    public SemanticGuardrailObservation {
        if (boundary == null || action == null || pathResult == null) {
            throw new IllegalArgumentException("boundary, action, and pathResult must not be null");
        }
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
        reasons = List.copyOf(reasons);
    }
}
