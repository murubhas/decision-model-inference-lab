package dev.decisionmodel.lab.guardrail.runtime;

public enum HybridDecisionProvider {
    JEV,
    STRANDS_DECIDER;

    HybridFallbackReason clearReason() {
        return switch (this) {
            case JEV -> HybridFallbackReason.JEV_CLEAR;
            case STRANDS_DECIDER -> HybridFallbackReason.DECIDER_CLEAR;
        };
    }

    HybridFallbackReason uncertainReason() {
        return switch (this) {
            case JEV -> HybridFallbackReason.JEV_UNCERTAIN;
            case STRANDS_DECIDER -> HybridFallbackReason.DECIDER_UNCERTAIN;
        };
    }

    HybridFallbackReason unavailableReason() {
        return switch (this) {
            case JEV -> HybridFallbackReason.JEV_UNAVAILABLE;
            case STRANDS_DECIDER -> HybridFallbackReason.DECIDER_UNAVAILABLE;
        };
    }
}
