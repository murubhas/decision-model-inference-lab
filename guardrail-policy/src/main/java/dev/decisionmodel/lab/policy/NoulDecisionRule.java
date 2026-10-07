package dev.decisionmodel.lab.policy;

import dev.decisionmodel.lab.guardrail.GuardrailAction;

public record NoulDecisionRule(
        String questionId,
        NoulRiskDirection riskDirection,
        double escalateBoundary,
        double blockBoundary) {

    public NoulDecisionRule {
        if (questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("questionId must not be blank");
        }
        if (riskDirection == null) {
            throw new IllegalArgumentException("riskDirection must not be null");
        }
        requireProbability(escalateBoundary, "escalateBoundary");
        requireProbability(blockBoundary, "blockBoundary");
        if (riskDirection == NoulRiskDirection.HIGH_TRUE && escalateBoundary >= blockBoundary) {
            throw new IllegalArgumentException("HIGH_TRUE escalation must be below block");
        }
        if (riskDirection == NoulRiskDirection.LOW_TRUE && blockBoundary >= escalateBoundary) {
            throw new IllegalArgumentException("LOW_TRUE block must be below escalation");
        }
    }

    public GuardrailAction classify(double probabilityTrue) {
        requireProbability(probabilityTrue, "probabilityTrue");
        if (riskDirection == NoulRiskDirection.HIGH_TRUE) {
            if (probabilityTrue >= blockBoundary) {
                return GuardrailAction.BLOCK;
            }
            return probabilityTrue >= escalateBoundary ? GuardrailAction.ESCALATE : GuardrailAction.ALLOW;
        }
        if (probabilityTrue <= blockBoundary) {
            return GuardrailAction.BLOCK;
        }
        return probabilityTrue < escalateBoundary ? GuardrailAction.ESCALATE : GuardrailAction.ALLOW;
    }

    private static void requireProbability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
