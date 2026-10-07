package dev.decisionmodel.lab.a2a;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

@RequestScoped
public class GuardrailInvocationContext {

    private static final int MAX_TOOL_RESULTS = 8;
    private static final int MAX_EVIDENCE_CHARACTERS = 48_000;
    private static final int MAX_SINGLE_RESULT_CHARACTERS = 12_000;

    private final List<SemanticGuardrailObservation> outputObservations = new CopyOnWriteArrayList<>();
    private final List<String> providerFailures = new CopyOnWriteArrayList<>();
    private final List<ToolEvidence> toolEvidence = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile String correlationId;
    private volatile String userInput;
    private volatile InputGuardrailResult inputObservation;

    @Inject
    GuardrailTelemetry telemetry;

    public void begin(String userInput) {
        this.correlationId = UUID.randomUUID().toString();
        this.userInput = userInput;
        this.inputObservation = null;
        this.outputObservations.clear();
        this.providerFailures.clear();
        this.toolEvidence.clear();
        this.sequence.set(0);
    }

    public String userInput() {
        return userInput;
    }

    public String nextCorrelationId(GuardrailBoundary boundary) {
        String root = correlationId;
        if (root == null) {
            root = UUID.randomUUID().toString();
            correlationId = root;
        }
        return root + "-" + boundary.name().toLowerCase() + "-" + sequence.incrementAndGet();
    }

    public void recordInput(InputGuardrailResult result) {
        this.inputObservation = result;
        if (telemetry != null) {
            telemetry.recordDecision(
                    GuardrailBoundary.INPUT,
                    result.action(),
                    result.reasons(),
                    result.pathResult());
        }
    }

    public Optional<InputGuardrailResult> inputObservation() {
        return Optional.ofNullable(inputObservation);
    }

    public void recordOutput(SemanticGuardrailObservation observation) {
        outputObservations.add(observation);
        if (telemetry != null) {
            telemetry.recordDecision(
                    observation.boundary(),
                    observation.action(),
                    observation.reasons(),
                    observation.pathResult());
        }
    }

    public List<SemanticGuardrailObservation> outputObservations() {
        return List.copyOf(outputObservations);
    }

    public void recordProviderFailure(GuardrailBoundary boundary, RuntimeException exception) {
        providerFailures.add(boundary.name() + ":" + exception.getClass().getName());
        if (telemetry != null) {
            telemetry.recordProviderFailure(boundary, exception);
        }
    }

    public List<String> providerFailures() {
        return List.copyOf(providerFailures);
    }

    public synchronized void recordToolEvidence(String toolName, String result) {
        if (toolEvidence.size() >= MAX_TOOL_RESULTS) {
            return;
        }
        String name = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        String text = result == null ? "" : result;
        int used = toolEvidence.stream().mapToInt(item -> item.result().length()).sum();
        int remaining = MAX_EVIDENCE_CHARACTERS - used;
        if (remaining <= 0) {
            return;
        }
        int length = Math.min(text.length(), Math.min(remaining, MAX_SINGLE_RESULT_CHARACTERS));
        toolEvidence.add(new ToolEvidence(name, text.substring(0, length), length < text.length()));
    }

    public List<Map<String, String>> toolEvidenceState() {
        return toolEvidence.stream().map(item -> {
            Map<String, String> state = new LinkedHashMap<>();
            state.put("tool", item.toolName());
            state.put("result", item.result());
            state.put("truncated", Boolean.toString(item.truncated()));
            return Map.copyOf(state);
        }).toList();
    }

    private record ToolEvidence(String toolName, String result, boolean truncated) {
    }
}
