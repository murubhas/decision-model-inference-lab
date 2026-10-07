package dev.decisionmodel.lab.benchmark;

import java.util.List;
import java.util.Map;

import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;

public record BenchmarkReport(
        String schemaVersion,
        String generatedAt,
        String runId,
        String corpusSha256,
        int corpusCases,
        int approvedCases,
        int draftCases,
        int warmupObservations,
        int measuredObservations,
        Map<SemanticPath, PathMetrics> paths,
        EconomicsSummary economics,
        List<BenchmarkObservation> observations) {

    public static final String SCHEMA_VERSION = "3.0";

    public BenchmarkReport {
        paths = Map.copyOf(paths);
        if (economics == null) {
            throw new IllegalArgumentException("economics must not be null");
        }
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("observations must not be empty");
        }
        observations = List.copyOf(observations);
    }
}
