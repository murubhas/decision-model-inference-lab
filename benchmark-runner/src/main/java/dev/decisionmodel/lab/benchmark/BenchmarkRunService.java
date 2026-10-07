package dev.decisionmodel.lab.benchmark;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.runtime.HybridAssessment;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.policy.PolicyDecision;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

@ApplicationScoped
public class BenchmarkRunService {

    private final SemanticPathRunner runner;
    private final BenchmarkThresholds thresholds;

    @Inject
    public BenchmarkRunService(SemanticPathRunner runner, BenchmarkThresholds thresholds) {
        this.runner = runner;
        this.thresholds = thresholds;
    }

    @ActivateRequestContext
    public List<BenchmarkObservation> run(
            BenchmarkCorpus corpus,
            String runId,
            List<SemanticPath> paths,
            int warmupIterations,
            int measuredIterations) {
        if (corpus == null) {
            throw new IllegalArgumentException("corpus must not be null");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        List<SemanticPath> orderedPaths = validatePaths(paths);
        if (warmupIterations < 0 || measuredIterations < 1) {
            throw new IllegalArgumentException("warmups must be non-negative and repetitions must be positive");
        }

        List<BenchmarkObservation> observations = new ArrayList<>();
        int sequence = 0;
        int globalIteration = 0;
        for (SamplePhase phase : SamplePhase.values()) {
            int iterations = phase == SamplePhase.WARMUP ? warmupIterations : measuredIterations;
            for (int iteration = 0; iteration < iterations; iteration++) {
                for (int caseIndex = 0; caseIndex < corpus.cases().size(); caseIndex++) {
                    BenchmarkCase benchmarkCase = corpus.cases().get(caseIndex);
                    List<SemanticPath> rotated = rotate(
                            orderedPaths,
                            Math.floorMod(globalIteration + caseIndex, orderedPaths.size()));
                    for (SemanticPath path : rotated) {
                        observations.add(execute(
                                corpus,
                                benchmarkCase,
                                runId,
                                path,
                                phase,
                                iteration,
                                sequence++));
                    }
                }
                globalIteration++;
            }
        }
        return List.copyOf(observations);
    }

    private BenchmarkObservation execute(
            BenchmarkCorpus corpus,
            BenchmarkCase benchmarkCase,
            String runId,
            SemanticPath path,
            SamplePhase phase,
            int iteration,
            int sequence) {
        long started = System.nanoTime();
        GuardrailRequest request = benchmarkCase.request(
                runId + ":" + sequence + ":" + benchmarkCase.caseId());
        try {
            SemanticPathResult result = runner.run(path, request);
            long elapsed = System.nanoTime() - started;
            GuardrailEvaluation selected = result.selectedEvaluation();
            PolicyDecision decision = benchmarkCase.policy(thresholds).decide(selected);

            Map<String, ObservedNoulAnswer> answers = new LinkedHashMap<>();
            selected.answers().forEach((id, answer) -> answers.put(id, observedNoul(id, answer)));

            List<ProviderCallObservation> providerCalls = new ArrayList<>();
            long inputTokens = 0;
            long outputTokens = 0;
            for (int index = 0; index < result.evaluations().size(); index++) {
                GuardrailEvaluation evaluation = result.evaluations().get(index);
                inputTokens += evaluation.usage().inputTokens();
                outputTokens += evaluation.usage().outputTokens();
                GuardrailRequest providerRequest = path.isHybrid()
                        ? GuardrailPolicyCatalog.hybridRoutingPolicy()
                                .project(request, evaluation.answers().keySet())
                        : request;
                providerCalls.add(new ProviderCallObservation(
                        evaluation.provider(),
                        evaluation.model(),
                        SemanticInputObservation.from(providerRequest),
                        observedAnswers(evaluation),
                        evaluation.latency().toNanos(),
                        evaluation.usage().inputTokens(),
                        evaluation.usage().outputTokens(),
                        result.contributes(index)));
            }

            HybridAssessment assessment = result.hybridAssessment().orElse(null);
            return new BenchmarkObservation(
                    BenchmarkObservation.SCHEMA_VERSION,
                    runId,
                    Instant.now().toString(),
                    corpus.sha256(),
                    sequence,
                    iteration,
                    phase,
                    benchmarkCase.caseId(),
                    benchmarkCase.boundary(),
                    path,
                    benchmarkCase.riskTags(),
                    benchmarkCase.input(),
                    ObservationStatus.SUCCESS,
                    benchmarkCase.expectedNoul(),
                    benchmarkCase.expectedAction(),
                    decision.action(),
                    answers,
                    providerCalls,
                    selected.provider(),
                    selected.model(),
                    elapsed,
                    inputTokens,
                    outputTokens,
                    assessment != null && assessment.fallbackRequired(),
                    assessment == null ? null : assessment.reason().name(),
                    null);
        } catch (RuntimeException exception) {
            return new BenchmarkObservation(
                    BenchmarkObservation.SCHEMA_VERSION,
                    runId,
                    Instant.now().toString(),
                    corpus.sha256(),
                    sequence,
                    iteration,
                    phase,
                    benchmarkCase.caseId(),
                    benchmarkCase.boundary(),
                    path,
                    benchmarkCase.riskTags(),
                    benchmarkCase.input(),
                    ObservationStatus.ERROR,
                    benchmarkCase.expectedNoul(),
                    benchmarkCase.expectedAction(),
                    null,
                    Map.of(),
                    List.of(),
                    null,
                    null,
                    System.nanoTime() - started,
                    0,
                    0,
                    false,
                    null,
                    exception.getClass().getName());
        }
    }

    private static ObservedNoulAnswer observedNoul(String id, SemanticAnswer answer) {
        if (answer instanceof NoulAnswer noul) {
            return ObservedNoulAnswer.from(noul.probabilityTrue());
        }
        throw new IllegalArgumentException("benchmark v1 expected a Noul answer for '" + id + "'");
    }

    private static Map<String, ObservedNoulAnswer> observedAnswers(GuardrailEvaluation evaluation) {
        Map<String, ObservedNoulAnswer> answers = new LinkedHashMap<>();
        evaluation.answers().forEach((id, answer) -> answers.put(id, observedNoul(id, answer)));
        return answers;
    }

    private static List<SemanticPath> validatePaths(List<SemanticPath> paths) {
        if (paths == null || paths.isEmpty() || paths.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException("paths must not be empty");
        }
        LinkedHashSet<SemanticPath> unique = new LinkedHashSet<>(paths);
        if (unique.size() != paths.size()) {
            throw new IllegalArgumentException("paths must not contain duplicates");
        }
        return List.copyOf(unique);
    }

    private static List<SemanticPath> rotate(List<SemanticPath> paths, int distance) {
        List<SemanticPath> rotated = new ArrayList<>(paths);
        Collections.rotate(rotated, -distance);
        return rotated;
    }
}
