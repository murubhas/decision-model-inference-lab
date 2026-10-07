package dev.decisionmodel.lab.benchmark;

import java.math.BigDecimal;

public record SelfHostedCapacityMetrics(
        String provider,
        String model,
        int calls,
        BigDecimal computeUsdPerHour,
        double measuredWindowSeconds,
        double activeComputeSeconds,
        double observedUtilizationPercent,
        double observedCallsPerSecond,
        double saturatedCallsPerSecond,
        BigDecimal provisionedWindowCostUsd,
        BigDecimal activeComputeCostUsd,
        BigDecimal provisionedCostPer1000CallsUsd,
        BigDecimal activeComputeCostPer1000CallsUsd,
        String sourceUrl,
        String sourcePublishedAt) {
}
