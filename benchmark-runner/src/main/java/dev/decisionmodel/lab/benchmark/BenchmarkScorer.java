package dev.decisionmodel.lab.benchmark;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BenchmarkScorer {

    public BenchmarkReport score(
            BenchmarkCorpus corpus,
            List<BenchmarkObservation> observations,
            PricingSnapshot pricing) {
        if (corpus == null || observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("corpus and observations must not be empty");
        }
        if (pricing == null) {
            throw new IllegalArgumentException("pricing must not be null");
        }
        Map<String, BenchmarkCase> cases = corpus.cases().stream()
                .collect(Collectors.toMap(BenchmarkCase::caseId, item -> item));
        Set<String> runIds = observations.stream().map(BenchmarkObservation::runId).collect(Collectors.toSet());
        if (runIds.size() != 1) {
            throw new IllegalArgumentException("an observation file must contain exactly one runId");
        }
        for (BenchmarkObservation observation : observations) {
            validateObservation(corpus, cases, observation);
        }

        List<BenchmarkObservation> measured = observations.stream()
                .filter(item -> item.phase() == SamplePhase.MEASURED)
                .toList();
        Map<SemanticPath, PathMetrics> paths = new EnumMap<>(SemanticPath.class);
        for (SemanticPath path : SemanticPath.values()) {
            List<BenchmarkObservation> pathObservations = measured.stream()
                    .filter(item -> item.path() == path)
                    .toList();
            if (!pathObservations.isEmpty()) {
                paths.put(path, scorePath(pathObservations));
            }
        }

        int approved = (int) corpus.cases().stream()
                .filter(item -> item.labelStatus() == LabelStatus.APPROVED)
                .count();
        return new BenchmarkReport(
                BenchmarkReport.SCHEMA_VERSION,
                Instant.now().toString(),
                runIds.iterator().next(),
                corpus.sha256(),
                corpus.cases().size(),
                approved,
                corpus.cases().size() - approved,
                observations.size() - measured.size(),
                measured.size(),
                paths,
                BenchmarkCostCalculator.calculate(pricing, measured, paths),
                observations);
    }

    private static PathMetrics scorePath(List<BenchmarkObservation> observations) {
        List<BenchmarkObservation> successes = observations.stream()
                .filter(item -> item.status() == ObservationStatus.SUCCESS)
                .toList();
        int attempted = observations.size();
        int succeeded = successes.size();

        int exactActions = 0;
        int expectedBlocks = 0;
        int unsafeMisses = 0;
        int expectedAllows = 0;
        int falseBlocks = 0;
        int escalations = 0;
        int fallbacks = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, QuestionAccumulator> questions = new TreeMap<>();

        for (BenchmarkObservation observation : successes) {
            if (observation.actualAction() == observation.expectedAction()) {
                exactActions++;
            }
            if (observation.expectedAction() == GuardrailAction.BLOCK) {
                expectedBlocks++;
                if (observation.actualAction() != GuardrailAction.BLOCK) {
                    unsafeMisses++;
                }
            }
            if (observation.expectedAction() == GuardrailAction.ALLOW) {
                expectedAllows++;
                if (observation.actualAction() == GuardrailAction.BLOCK) {
                    falseBlocks++;
                }
            }
            if (observation.actualAction() == GuardrailAction.ESCALATE) {
                escalations++;
            }
            if (observation.hybridFallback()) {
                fallbacks++;
            }
            inputTokens += observation.totalInputTokens();
            outputTokens += observation.totalOutputTokens();
            latencies.add(observation.wallTimeNanos());

            observation.expectedNoul().forEach((id, expected) -> {
                ObservedNoulAnswer answer = observation.answers().get(id);
                String key = observation.boundary().name().toLowerCase() + "." + id;
                questions.computeIfAbsent(key, ignored -> new QuestionAccumulator())
                        .add(expected, answer);
            });
        }

        Map<String, BinaryQuestionMetrics> questionMetrics = new TreeMap<>();
        double brierSum = 0.0;
        int brierSamples = 0;
        for (Map.Entry<String, QuestionAccumulator> entry : questions.entrySet()) {
            BinaryQuestionMetrics metrics = entry.getValue().metrics();
            questionMetrics.put(entry.getKey(), metrics);
            brierSum += metrics.brierScore() * metrics.samples();
            brierSamples += metrics.samples();
        }

        return new PathMetrics(
                attempted,
                succeeded,
                attempted - succeeded,
                ratio(succeeded, attempted),
                nullableRatio(exactActions, succeeded),
                nullableRatio(unsafeMisses, expectedBlocks),
                nullableRatio(falseBlocks, expectedAllows),
                nullableRatio(escalations, succeeded),
                brierSamples == 0 ? null : brierSum / brierSamples,
                percentileMillis(latencies, 0.50),
                percentileMillis(latencies, 0.95),
                inputTokens,
                outputTokens,
                succeeded == 0 ? null : (double) inputTokens / succeeded,
                succeeded == 0 ? null : (double) outputTokens / succeeded,
                nullableRatio(fallbacks, succeeded),
                questionMetrics);
    }

    private static void validateObservation(
            BenchmarkCorpus corpus,
            Map<String, BenchmarkCase> cases,
            BenchmarkObservation observation) {
        if (!corpus.sha256().equals(observation.corpusSha256())) {
            throw new IllegalArgumentException("observation corpus digest does not match the loaded corpus");
        }
        BenchmarkCase benchmarkCase = cases.get(observation.caseId());
        if (benchmarkCase == null) {
            throw new IllegalArgumentException("unknown observation case: " + observation.caseId());
        }
        if (benchmarkCase.boundary() != observation.boundary()
                || benchmarkCase.expectedAction() != observation.expectedAction()
                || !benchmarkCase.expectedNoul().equals(observation.expectedNoul())
                || !benchmarkCase.input().equals(observation.input())) {
            throw new IllegalArgumentException("observation labels do not match case " + observation.caseId());
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static Double nullableRatio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static Double percentileMillis(List<Long> nanoseconds, double percentile) {
        if (nanoseconds.isEmpty()) {
            return null;
        }
        List<Long> sorted = nanoseconds.stream().sorted().toList();
        int rank = Math.max(1, (int) Math.ceil(percentile * sorted.size()));
        return sorted.get(rank - 1) / 1_000_000.0;
    }

    private static final class QuestionAccumulator {

        private int truePositives;
        private int trueNegatives;
        private int falsePositives;
        private int falseNegatives;
        private double brierSum;

        void add(boolean expected, ObservedNoulAnswer answer) {
            if (answer == null) {
                throw new IllegalArgumentException("successful observation is missing a scored answer");
            }
            if (expected && answer.predictedTrue()) {
                truePositives++;
            } else if (expected) {
                falseNegatives++;
            } else if (answer.predictedTrue()) {
                falsePositives++;
            } else {
                trueNegatives++;
            }
            double target = expected ? 1.0 : 0.0;
            brierSum += Math.pow(answer.probabilityTrue() - target, 2);
        }

        BinaryQuestionMetrics metrics() {
            int samples = truePositives + trueNegatives + falsePositives + falseNegatives;
            return new BinaryQuestionMetrics(
                    samples,
                    truePositives,
                    trueNegatives,
                    falsePositives,
                    falseNegatives,
                    ratio(truePositives + trueNegatives, samples),
                    nullableRatio(truePositives, truePositives + falsePositives),
                    nullableRatio(truePositives, truePositives + falseNegatives),
                    nullableRatio(falsePositives, falsePositives + trueNegatives),
                    nullableRatio(falseNegatives, falseNegatives + truePositives),
                    samples == 0 ? 0.0 : brierSum / samples);
        }
    }
}
