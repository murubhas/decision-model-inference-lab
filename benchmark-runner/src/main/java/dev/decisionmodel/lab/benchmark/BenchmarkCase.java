package dev.decisionmodel.lab.benchmark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.decisionmodel.lab.policy.NoulDecisionPolicy;

public record BenchmarkCase(
        String schemaVersion,
        String caseId,
        GuardrailBoundary boundary,
        LabelStatus labelStatus,
        List<String> riskTags,
        List<String> sourceRefs,
        String userRequest,
        String toolName,
        String toolArguments,
        String assistantResponse,
        List<Map<String, String>> toolEvidence,
        Map<String, Boolean> expectedNoul,
        GuardrailAction expectedAction,
        String rationale) {

    public static final String SCHEMA_VERSION = "1.0";

    public BenchmarkCase {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported case schemaVersion: " + schemaVersion);
        }
        caseId = requireText(caseId, "caseId");
        if (boundary == null || labelStatus == null || expectedAction == null) {
            throw new IllegalArgumentException("boundary, labelStatus, and expectedAction are required");
        }
        if (boundary == GuardrailBoundary.TOOL_RESULT) {
            throw new IllegalArgumentException(
                    "benchmark v1 evaluates MCP results as evidence at the OUTPUT boundary");
        }
        riskTags = copyTags(riskTags);
        sourceRefs = copyOptionalTexts(sourceRefs, "sourceRef");
        if (riskTags.contains("source-derived") && sourceRefs.isEmpty()) {
            throw new IllegalArgumentException("source-derived cases require at least one sourceRef");
        }
        toolEvidence = copyEvidence(toolEvidence);
        expectedNoul = copyLabels(expectedNoul);
        rationale = requireText(rationale, "rationale");
        validatePayload(boundary, userRequest, toolName, toolArguments, assistantResponse);
        if (!expectedNoul.keySet().equals(expectedQuestionIds(boundary))) {
            throw new IllegalArgumentException(
                    "expectedNoul keys do not match the shared policy for " + boundary);
        }
    }

    public GuardrailRequest request(String correlationId) {
        return switch (boundary) {
            case INPUT -> GuardrailPolicyCatalog.inputRequest(correlationId, userRequest);
            case TOOL_CALL -> GuardrailPolicyCatalog.toolCallRequest(
                    correlationId, userRequest, toolName, toolArguments);
            case OUTPUT -> GuardrailPolicyCatalog.outputRequest(
                    correlationId, userRequest, assistantResponse, toolEvidence);
            case TOOL_RESULT -> throw new IllegalStateException("TOOL_RESULT is not supported in benchmark v1");
        };
    }

    public Map<String, Object> input() {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("userRequest", userRequest);
        if (boundary == GuardrailBoundary.TOOL_CALL) {
            input.put("toolName", toolName);
            input.put("toolArguments", toolArguments);
        } else if (boundary == GuardrailBoundary.OUTPUT) {
            input.put("assistantResponse", assistantResponse);
            input.put("toolEvidence", toolEvidence);
        }
        return Collections.unmodifiableMap(input);
    }

    public NoulDecisionPolicy policy(BenchmarkThresholds thresholds) {
        return thresholds.policy(boundary);
    }

    private static Set<String> expectedQuestionIds(GuardrailBoundary boundary) {
        return switch (boundary) {
            case INPUT -> Set.of(
                    GuardrailPolicyCatalog.PROMPT_INJECTION,
                    GuardrailPolicyCatalog.IN_SCOPE);
            case TOOL_CALL -> Set.of(
                    GuardrailPolicyCatalog.TOOL_INTENT_ALIGNED,
                    GuardrailPolicyCatalog.TOOL_ARGUMENTS_SAFE);
            case OUTPUT -> Set.of(
                    GuardrailPolicyCatalog.RESPONSE_RELEVANT,
                    GuardrailPolicyCatalog.RESPONSE_GROUNDED,
                    GuardrailPolicyCatalog.SENSITIVE_EXPOSURE,
                    GuardrailPolicyCatalog.POLICY_LEAKAGE);
            case TOOL_RESULT -> throw new IllegalStateException("TOOL_RESULT is not supported in benchmark v1");
        };
    }

    private static void validatePayload(
            GuardrailBoundary boundary,
            String userRequest,
            String toolName,
            String toolArguments,
            String assistantResponse) {
        requireText(userRequest, "userRequest");
        if (boundary == GuardrailBoundary.TOOL_CALL) {
            requireText(toolName, "toolName");
            requireText(toolArguments, "toolArguments");
        }
        if (boundary == GuardrailBoundary.OUTPUT) {
            requireText(assistantResponse, "assistantResponse");
        }
    }

    private static List<String> copyTags(List<String> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("riskTags must not be empty");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String tag : source) {
            values.add(requireText(tag, "riskTag"));
        }
        return List.copyOf(values);
    }

    private static List<String> copyOptionalTexts(List<String> source, String field) {
        if (source == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : source) {
            values.add(requireText(value, field));
        }
        return List.copyOf(values);
    }

    private static List<Map<String, String>> copyEvidence(List<Map<String, String>> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream().map(item -> {
            if (item == null) {
                throw new IllegalArgumentException("toolEvidence must not contain null values");
            }
            return Map.copyOf(item);
        }).toList();
    }

    private static Map<String, Boolean> copyLabels(Map<String, Boolean> source) {
        if (source == null || source.isEmpty() || source.values().stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("expectedNoul must contain non-null labels");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
