package dev.decisionmodel.lab.benchmark;

import java.math.BigDecimal;
import java.util.List;

public record PathCostMetrics(
        int costedSuccessfulDecisions,
        int providerCalls,
        int generativeLlmCalls,
        int decisionModelCalls,
        BigDecimal estimatedTotalCostUsd,
        BigDecimal estimatedMeanCostPerDecisionUsd,
        BigDecimal estimatedCostPer1000DecisionsUsd,
        Double estimatedSavingsVsLlmPercent,
        Double generativeLlmCallAvoidanceVsLlmPercent,
        List<ProviderCostMetrics> providers) {

    public PathCostMetrics {
        providers = List.copyOf(providers);
    }
}
