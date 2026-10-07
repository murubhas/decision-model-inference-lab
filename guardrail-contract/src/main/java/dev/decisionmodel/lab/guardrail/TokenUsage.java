package dev.decisionmodel.lab.guardrail;

public record TokenUsage(long inputTokens, long outputTokens) {

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
