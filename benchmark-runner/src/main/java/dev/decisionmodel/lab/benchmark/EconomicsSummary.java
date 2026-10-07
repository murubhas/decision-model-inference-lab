package dev.decisionmodel.lab.benchmark;

import java.util.List;
import java.util.Map;

import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;

public record EconomicsSummary(
        String pricingCatalogId,
        String pricingCatalogSha256,
        String pricingCheckedAt,
        String currency,
        List<String> caveats,
        Double observedHybridFallbackRate,
        Double estimatedHybridBreakEvenFallbackRate,
        Double observedHybridDeciderFallbackRate,
        Double estimatedHybridDeciderBreakEvenFallbackRate,
        List<SelfHostedCapacityMetrics> selfHostedCapacity,
        Map<SemanticPath, PathCostMetrics> paths) {

    public EconomicsSummary {
        caveats = List.copyOf(caveats);
        selfHostedCapacity = List.copyOf(selfHostedCapacity);
        paths = Map.copyOf(paths);
    }
}
