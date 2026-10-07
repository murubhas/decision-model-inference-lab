package dev.decisionmodel.lab.benchmark;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(
        name = "jev-benchmark",
        mixinStandardHelpOptions = true,
        description = "Validate, execute, or score the decision-model guardrail comparison corpus.")
public class BenchmarkCommand implements Callable<Integer> {

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    @CommandLine.Option(names = "--mode", defaultValue = "VALIDATE")
    BenchmarkMode mode;

    @CommandLine.Option(
            names = "--corpus",
            defaultValue = "datasets/guardrail-corpus.v1.jsonl")
    Path corpusPath;

    @CommandLine.Option(
            names = "--observations",
            defaultValue = "results/benchmark-observations.jsonl")
    Path observationsPath;

    @CommandLine.Option(
            names = "--pricing",
            defaultValue = "datasets/provider-pricing.v1.json")
    Path pricingPath;

    @CommandLine.Option(
            names = "--report",
            defaultValue = "results/benchmark-report.json")
    Path reportPath;

    @CommandLine.Option(
            names = "--paths",
            split = ",",
            defaultValue = "LLM,JEV,HYBRID")
    List<SemanticPath> paths;

    @CommandLine.Option(names = "--warmups", defaultValue = "0")
    int warmups;

    @CommandLine.Option(names = "--repetitions", defaultValue = "1")
    int repetitions;

    @CommandLine.Option(names = "--run-id")
    String runId;

    @CommandLine.Option(
            names = "--allow-paid-provider-calls",
            description = "Required for RUN mode because it invokes Bedrock and/or TypeSafe.")
    boolean allowPaidProviderCalls;

    @CommandLine.Option(
            names = "--allow-draft-labels",
            description = "Permit an exploratory run before human label approval.")
    boolean allowDraftLabels;

    @Inject
    BenchmarkIo io;

    @Inject
    BenchmarkRunService runService;

    @Inject
    BenchmarkScorer scorer;

    @Override
    public Integer call() {
        try {
            BenchmarkCorpus corpus = io.loadCorpus(corpusPath);
            PricingSnapshot pricing = io.loadPricing(pricingPath);
            return switch (mode) {
                case VALIDATE -> validate(corpus, pricing);
                case RUN -> run(corpus, pricing);
                case SCORE -> score(corpus, pricing);
            };
        } catch (Exception exception) {
            System.err.printf("Benchmark failed (%s): %s%n",
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            return 2;
        }
    }

    private int validate(BenchmarkCorpus corpus, PricingSnapshot pricing) {
        System.out.printf(
                "Validated corpus: cases=%d, draft=%d, sha256=%s, pricing=%s, pricingSha256=%s%n",
                corpus.cases().size(),
                corpus.draftCount(),
                corpus.sha256(),
                pricing.catalog().catalogId(),
                pricing.sha256());
        return 0;
    }

    private int run(BenchmarkCorpus corpus, PricingSnapshot pricing) throws Exception {
        if (!allowPaidProviderCalls) {
            System.err.println("RUN requires --allow-paid-provider-calls.");
            return 2;
        }
        if (corpus.draftCount() > 0 && !allowDraftLabels) {
            System.err.println("Corpus contains draft labels; review them or pass --allow-draft-labels for a pilot.");
            return 2;
        }

        String effectiveRunId = runId == null || runId.isBlank() ? generatedRunId() : runId;
        List<BenchmarkObservation> observations = runService.run(
                corpus,
                effectiveRunId,
                paths,
                warmups,
                repetitions);
        io.writeObservations(observationsPath, observations);
        BenchmarkReport report = scorer.score(corpus, observations, pricing);
        io.writeReport(reportPath, report);

        long failures = observations.stream()
                .filter(item -> item.status() == ObservationStatus.ERROR)
                .count();
        System.out.printf(
                "Completed run %s: observations=%d, failures=%d, output=%s, report=%s%n",
                effectiveRunId,
                observations.size(),
                failures,
                observationsPath,
                reportPath);
        return failures == 0 ? 0 : 1;
    }

    private int score(BenchmarkCorpus corpus, PricingSnapshot pricing) throws Exception {
        List<BenchmarkObservation> observations = io.loadObservations(observationsPath);
        BenchmarkReport report = scorer.score(corpus, observations, pricing);
        io.writeReport(reportPath, report);
        System.out.printf(
                "Scored run %s: measured=%d, report=%s%n",
                report.runId(),
                report.measuredObservations(),
                reportPath);
        return 0;
    }

    private static String generatedRunId() {
        return "jev-" + RUN_ID_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
