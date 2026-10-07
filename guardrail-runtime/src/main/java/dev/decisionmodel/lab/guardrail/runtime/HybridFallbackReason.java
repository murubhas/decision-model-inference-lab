package dev.decisionmodel.lab.guardrail.runtime;

public enum HybridFallbackReason {
    JEV_CLEAR,
    JEV_UNCERTAIN,
    JEV_UNAVAILABLE,
    DECIDER_CLEAR,
    DECIDER_UNCERTAIN,
    DECIDER_UNAVAILABLE;

    public boolean isClear() {
        return this == JEV_CLEAR || this == DECIDER_CLEAR;
    }

    public boolean isUncertain() {
        return this == JEV_UNCERTAIN || this == DECIDER_UNCERTAIN;
    }
}
