package dev.decisionmodel.lab.policy;

import java.util.List;

import dev.decisionmodel.lab.guardrail.GuardrailAction;

public record PolicyDecision(GuardrailAction action, List<String> reasons) {

    public PolicyDecision {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("reasons must not be empty");
        }
        reasons = List.copyOf(reasons);
    }
}
