package dev.decisionmodel.lab.benchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record BenchmarkCorpus(List<BenchmarkCase> cases, String sha256) {

    public BenchmarkCorpus {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases must not be empty");
        }
        cases = List.copyOf(cases);
        Set<String> ids = new HashSet<>();
        for (BenchmarkCase benchmarkCase : cases) {
            if (benchmarkCase == null) {
                throw new IllegalArgumentException("cases must not contain null values");
            }
            if (!ids.add(benchmarkCase.caseId())) {
                throw new IllegalArgumentException("duplicate benchmark case: " + benchmarkCase.caseId());
            }
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
        }
    }

    public long draftCount() {
        return cases.stream().filter(item -> item.labelStatus() == LabelStatus.DRAFT).count();
    }
}
