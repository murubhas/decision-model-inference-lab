package dev.decisionmodel.lab.benchmark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderCallObservation(
        String provider,
        String model,
        SemanticInputObservation semanticInput,
        Map<String, ObservedNoulAnswer> output,
        long latencyNanos,
        long inputTokens,
        long outputTokens,
        boolean selected) {

    public ProviderCallObservation {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("provider and model must not be blank");
        }
        if (semanticInput == null || output == null || output.isEmpty()) {
            throw new IllegalArgumentException("semanticInput and output must not be empty");
        }
        output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
        if (!output.keySet().equals(semanticInput.questions().keySet())) {
            throw new IllegalArgumentException("provider output ids must match semantic input question ids");
        }
        if (latencyNanos < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("latency and token counts must not be negative");
        }
    }
}
