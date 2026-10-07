package dev.decisionmodel.lab.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;

final class BenchmarkCostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);
    private static final int MONEY_SCALE = 12;

    private BenchmarkCostCalculator() {
    }

    static EconomicsSummary calculate(
            PricingSnapshot pricing,
            List<BenchmarkObservation> measured,
            Map<SemanticPath, PathMetrics> qualityMetrics) {
        Map<SemanticPath, RawPathCost> rawCosts = new EnumMap<>(SemanticPath.class);
        for (SemanticPath path : qualityMetrics.keySet()) {
            List<BenchmarkObservation> successes = measured.stream()
                    .filter(item -> item.path() == path && item.status() == ObservationStatus.SUCCESS)
                    .toList();
            rawCosts.put(path, calculatePath(pricing.catalog(), successes));
        }

        RawPathCost llmBaseline = rawCosts.get(SemanticPath.LLM);
        Map<SemanticPath, PathCostMetrics> paths = new EnumMap<>(SemanticPath.class);
        rawCosts.forEach((path, raw) -> paths.put(path, raw.toMetrics(llmBaseline)));

        RawPathCost jev = rawCosts.get(SemanticPath.JEV);
        Double breakEvenFallbackRate = llmBaseline == null || jev == null
                ? null
                : breakEvenFallbackRate(jev.meanCost(), llmBaseline.meanCost());
        PathMetrics hybridQuality = qualityMetrics.get(SemanticPath.HYBRID);
        RawPathCost decider = rawCosts.get(SemanticPath.DECIDER);
        Double deciderBreakEvenFallbackRate = llmBaseline == null || decider == null
                ? null
                : breakEvenFallbackRate(decider.meanCost(), llmBaseline.meanCost());
        PathMetrics hybridDeciderQuality = qualityMetrics.get(SemanticPath.HYBRID_DECIDER);

        PricingCatalog catalog = pricing.catalog();
        return new EconomicsSummary(
                catalog.catalogId(),
                pricing.sha256(),
                catalog.checkedAt(),
                catalog.currency(),
                catalog.caveats(),
                hybridQuality == null ? null : hybridQuality.hybridFallbackRate(),
                breakEvenFallbackRate,
                hybridDeciderQuality == null ? null : hybridDeciderQuality.hybridFallbackRate(),
                deciderBreakEvenFallbackRate,
                selfHostedCapacity(catalog, measured),
                paths);
    }

    private static RawPathCost calculatePath(
            PricingCatalog pricing,
            List<BenchmarkObservation> successes) {
        Map<ProviderKey, ProviderAccumulator> providers = new TreeMap<>();
        int generativeCalls = 0;
        int decisionCalls = 0;

        for (BenchmarkObservation observation : successes) {
            for (ProviderCallObservation call : observation.providerCalls()) {
                ProviderPricing rate = pricing.rateFor(call.provider(), call.model());
                ProviderKey key = new ProviderKey(call.provider(), call.model());
                providers.computeIfAbsent(key, ignored -> new ProviderAccumulator(rate)).add(call);
                if (rate.providerKind() == ProviderKind.GENERATIVE_LLM) {
                    generativeCalls++;
                } else {
                    decisionCalls++;
                }
            }
        }

        List<ProviderCostMetrics> providerMetrics = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int calls = 0;
        for (Map.Entry<ProviderKey, ProviderAccumulator> entry : providers.entrySet()) {
            ProviderCostMetrics metrics = entry.getValue().metrics(entry.getKey());
            providerMetrics.add(metrics);
            total = total.add(metrics.estimatedTotalCostUsd());
            calls += metrics.calls();
        }
        return new RawPathCost(
                successes.size(),
                calls,
                generativeCalls,
                decisionCalls,
                money(total),
                List.copyOf(providerMetrics));
    }

    private static BigDecimal tokenCost(long tokens, BigDecimal usdPerMillion) {
        return money(usdPerMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(ONE_MILLION, MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal activeComputeCost(long nanos, BigDecimal usdPerHour) {
        return money(usdPerHour
                .multiply(BigDecimal.valueOf(nanos))
                .divide(NANOS_PER_SECOND, MONEY_SCALE, RoundingMode.HALF_UP)
                .divide(SECONDS_PER_HOUR, MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private static List<SelfHostedCapacityMetrics> selfHostedCapacity(
            PricingCatalog pricing,
            List<BenchmarkObservation> measured) {
        Map<ProviderKey, CapacityAccumulator> providers = new TreeMap<>();
        for (BenchmarkObservation observation : measured) {
            if (observation.status() != ObservationStatus.SUCCESS) {
                continue;
            }
            Instant completedAt = Instant.parse(observation.recordedAt());
            Instant startedAt = completedAt.minusNanos(observation.wallTimeNanos());
            for (ProviderCallObservation call : observation.providerCalls()) {
                ProviderPricing rate = pricing.rateFor(call.provider(), call.model());
                if (rate.billingBasis() != TokenBillingBasis.SELF_HOSTED_ACTIVE_COMPUTE) {
                    continue;
                }
                ProviderKey key = new ProviderKey(call.provider(), call.model());
                providers.computeIfAbsent(key, ignored -> new CapacityAccumulator(rate))
                        .add(call, startedAt, completedAt);
            }
        }
        return providers.entrySet().stream()
                .map(entry -> entry.getValue().metrics(entry.getKey()))
                .toList();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static Double savingsPercent(BigDecimal candidate, BigDecimal baseline) {
        if (candidate == null || baseline == null || baseline.signum() == 0) {
            return null;
        }
        return baseline.subtract(candidate)
                .divide(baseline, MONEY_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private static Double callAvoidancePercent(RawPathCost candidate, RawPathCost baseline) {
        if (candidate == null || baseline == null
                || candidate.successfulDecisions == 0 || baseline.successfulDecisions == 0) {
            return null;
        }
        double baselineCalls = (double) baseline.generativeLlmCalls / baseline.successfulDecisions;
        if (baselineCalls == 0.0) {
            return null;
        }
        double candidateCalls = (double) candidate.generativeLlmCalls / candidate.successfulDecisions;
        return (1.0 - candidateCalls / baselineCalls) * 100.0;
    }

    private static Double breakEvenFallbackRate(BigDecimal jevCost, BigDecimal llmCost) {
        if (jevCost == null || llmCost == null || llmCost.signum() == 0) {
            return null;
        }
        return BigDecimal.ONE.subtract(jevCost.divide(llmCost, MONEY_SCALE, RoundingMode.HALF_UP))
                .doubleValue();
    }

    private record ProviderKey(String provider, String model) implements Comparable<ProviderKey> {
        @Override
        public int compareTo(ProviderKey other) {
            int providerOrder = provider.compareTo(other.provider);
            return providerOrder != 0 ? providerOrder : model.compareTo(other.model);
        }
    }

    private static final class ProviderAccumulator {

        private final ProviderPricing pricing;
        private int calls;
        private long inputTokens;
        private long outputTokens;
        private long activeComputeNanos;

        private ProviderAccumulator(ProviderPricing pricing) {
            this.pricing = pricing;
        }

        void add(ProviderCallObservation call) {
            calls++;
            inputTokens += call.inputTokens();
            outputTokens += call.outputTokens();
            activeComputeNanos = Math.addExact(activeComputeNanos, call.latencyNanos());
        }

        ProviderCostMetrics metrics(ProviderKey key) {
            boolean selfHosted = pricing.billingBasis()
                    == TokenBillingBasis.SELF_HOSTED_ACTIVE_COMPUTE;
            BigDecimal inputCost = selfHosted
                    ? BigDecimal.ZERO
                    : tokenCost(inputTokens, pricing.inputUsdPerMillionTokens());
            BigDecimal outputCost = pricing.billingBasis() == TokenBillingBasis.INPUT_AND_OUTPUT
                    ? tokenCost(outputTokens, pricing.outputUsdPerMillionTokens())
                    : BigDecimal.ZERO;
            BigDecimal computeCost = selfHosted
                    ? activeComputeCost(activeComputeNanos, pricing.computeUsdPerHour())
                    : BigDecimal.ZERO;
            return new ProviderCostMetrics(
                    key.provider(),
                    key.model(),
                    pricing.providerKind(),
                    calls,
                    inputTokens,
                    outputTokens,
                    inputCost,
                    outputCost,
                    activeComputeNanos,
                    computeCost,
                    pricing.computeUsdPerHour(),
                    money(inputCost.add(outputCost).add(computeCost)),
                    pricing.billingBasis(),
                    pricing.sourceUrl(),
                    pricing.sourcePublishedAt());
        }
    }

    private static final class CapacityAccumulator {

        private final ProviderPricing pricing;
        private int calls;
        private long activeComputeNanos;
        private Instant startedAt;
        private Instant completedAt;

        private CapacityAccumulator(ProviderPricing pricing) {
            this.pricing = pricing;
        }

        void add(ProviderCallObservation call, Instant observationStart, Instant observationEnd) {
            calls++;
            activeComputeNanos = Math.addExact(activeComputeNanos, call.latencyNanos());
            if (startedAt == null || observationStart.isBefore(startedAt)) {
                startedAt = observationStart;
            }
            if (completedAt == null || observationEnd.isAfter(completedAt)) {
                completedAt = observationEnd;
            }
        }

        SelfHostedCapacityMetrics metrics(ProviderKey key) {
            long timestampWindowNanos = java.time.Duration.between(startedAt, completedAt).toNanos();
            long measuredWindowNanos = Math.max(timestampWindowNanos, activeComputeNanos);
            double windowSeconds = measuredWindowNanos / 1_000_000_000.0;
            double activeSeconds = activeComputeNanos / 1_000_000_000.0;
            BigDecimal activeCost = activeComputeCost(
                    activeComputeNanos, pricing.computeUsdPerHour());
            BigDecimal windowCost = activeComputeCost(
                    measuredWindowNanos, pricing.computeUsdPerHour());
            return new SelfHostedCapacityMetrics(
                    key.provider(),
                    key.model(),
                    calls,
                    pricing.computeUsdPerHour(),
                    windowSeconds,
                    activeSeconds,
                    activeSeconds / windowSeconds * 100.0,
                    calls / windowSeconds,
                    calls / activeSeconds,
                    windowCost,
                    activeCost,
                    money(windowCost.multiply(ONE_THOUSAND)
                            .divide(BigDecimal.valueOf(calls), MONEY_SCALE, RoundingMode.HALF_UP)),
                    money(activeCost.multiply(ONE_THOUSAND)
                            .divide(BigDecimal.valueOf(calls), MONEY_SCALE, RoundingMode.HALF_UP)),
                    pricing.sourceUrl(),
                    pricing.sourcePublishedAt());
        }
    }

    private record RawPathCost(
            int successfulDecisions,
            int providerCalls,
            int generativeLlmCalls,
            int decisionModelCalls,
            BigDecimal totalCost,
            List<ProviderCostMetrics> providers) {

        BigDecimal meanCost() {
            return successfulDecisions == 0
                    ? null
                    : money(totalCost.divide(
                            BigDecimal.valueOf(successfulDecisions),
                            MONEY_SCALE,
                            RoundingMode.HALF_UP));
        }

        PathCostMetrics toMetrics(RawPathCost llmBaseline) {
            BigDecimal mean = meanCost();
            return new PathCostMetrics(
                    successfulDecisions,
                    providerCalls,
                    generativeLlmCalls,
                    decisionModelCalls,
                    totalCost,
                    mean,
                    mean == null ? null : money(mean.multiply(ONE_THOUSAND)),
                    savingsPercent(mean, llmBaseline == null ? null : llmBaseline.meanCost()),
                    callAvoidancePercent(this, llmBaseline),
                    providers);
        }
    }
}
