package dev.decisionmodel.lab.a2a;

import java.util.List;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;

public record InputGuardrailResult(
        GuardrailAction action,
        List<String> reasons,
        SemanticPathResult pathResult) {

    public InputGuardrailResult {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
        reasons = List.copyOf(reasons);
        if (pathResult == null) {
            throw new IllegalArgumentException("pathResult must not be null");
        }
    }
}
