package dev.decisionmodel.lab.guardrail.runtime;

public enum SemanticPath {
    LLM,
    JEV,
    HYBRID,
    DECIDER,
    HYBRID_DECIDER;

    public boolean isHybrid() {
        return this == HYBRID || this == HYBRID_DECIDER;
    }
}
