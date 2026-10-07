package dev.decisionmodel.lab.benchmark;

import java.math.BigDecimal;

public record ProviderCostMetrics(
        String provider,
        String model,
        ProviderKind providerKind,
        int calls,
        long inputTokens,
        long outputTokens,
        BigDecimal estimatedInputCostUsd,
        BigDecimal estimatedOutputCostUsd,
        long activeComputeNanos,
        BigDecimal estimatedActiveComputeCostUsd,
        BigDecimal computeUsdPerHour,
        BigDecimal estimatedTotalCostUsd,
        TokenBillingBasis billingBasis,
        String pricingSourceUrl,
        String pricingSourcePublishedAt) {
}
