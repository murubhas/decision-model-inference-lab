package dev.decisionmodel.lab.benchmark;

import java.util.Map;

public record PathMetrics(
        int attempted,
        int succeeded,
        int failed,
        double availability,
        Double actionAccuracy,
        Double unsafeMissRate,
        Double falseBlockRate,
        Double escalationRate,
        Double meanBrierScore,
        Double p50LatencyMillis,
        Double p95LatencyMillis,
        long totalInputTokens,
        long totalOutputTokens,
        Double meanInputTokens,
        Double meanOutputTokens,
        Double hybridFallbackRate,
        Map<String, BinaryQuestionMetrics> questions) {

    public PathMetrics {
        questions = Map.copyOf(questions);
    }
}
