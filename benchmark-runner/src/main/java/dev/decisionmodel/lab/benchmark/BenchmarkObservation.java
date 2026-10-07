package dev.decisionmodel.lab.benchmark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;

public record BenchmarkObservation(
        String schemaVersion,
        String runId,
        String recordedAt,
        String corpusSha256,
        int sequence,
        int iteration,
        SamplePhase phase,
        String caseId,
        GuardrailBoundary boundary,
        SemanticPath path,
        List<String> riskTags,
        Map<String, Object> input,
        ObservationStatus status,
        Map<String, Boolean> expectedNoul,
        GuardrailAction expectedAction,
        GuardrailAction actualAction,
        Map<String, ObservedNoulAnswer> answers,
        List<ProviderCallObservation> providerCalls,
        String selectedProvider,
        String selectedModel,
        long wallTimeNanos,
        long totalInputTokens,
        long totalOutputTokens,
        boolean hybridFallback,
        String hybridFallbackReason,
        String errorType) {

    public static final String SCHEMA_VERSION = "2.0";

    public BenchmarkObservation {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported observation schemaVersion: " + schemaVersion);
        }
        requireText(runId, "runId");
        requireText(recordedAt, "recordedAt");
        requireText(caseId, "caseId");
        if (corpusSha256 == null || !corpusSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("corpusSha256 must be a lowercase SHA-256 digest");
        }
        if (sequence < 0 || iteration < 0 || wallTimeNanos < 0
                || totalInputTokens < 0 || totalOutputTokens < 0) {
            throw new IllegalArgumentException("sequence, timing, and token counts must not be negative");
        }
        if (phase == null || boundary == null || path == null || status == null || expectedAction == null) {
            throw new IllegalArgumentException("phase, boundary, path, status, and expectedAction are required");
        }
        riskTags = riskTags == null ? List.of() : List.copyOf(riskTags);
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input must not be empty");
        }
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        expectedNoul = expectedNoul == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(expectedNoul));
        answers = answers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        providerCalls = providerCalls == null ? List.of() : List.copyOf(providerCalls);
        if (status == ObservationStatus.SUCCESS) {
            if (actualAction == null || selectedProvider == null || selectedModel == null || errorType != null) {
                throw new IllegalArgumentException("successful observations require a decision and selected provider");
            }
            if (!answers.keySet().equals(expectedNoul.keySet())) {
                throw new IllegalArgumentException("successful answer IDs must match expected labels");
            }
        } else if (actualAction != null || selectedProvider != null || selectedModel != null
                || errorType == null || errorType.isBlank()) {
            throw new IllegalArgumentException("error observations require only an errorType");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
