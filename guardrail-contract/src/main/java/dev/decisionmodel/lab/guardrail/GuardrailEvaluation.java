package dev.decisionmodel.lab.guardrail;

import java.time.Duration;
import java.util.Map;

public record GuardrailEvaluation(
        String provider,
        String model,
        Map<String, SemanticAnswer> answers,
        TokenUsage usage,
        Duration latency) {

    public GuardrailEvaluation {
        provider = ContractValidation.requireText(provider, "provider");
        model = ContractValidation.requireText(model, "model");
        answers = ContractValidation.orderedCopy(answers, "answers");
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("answers must not be empty");
        }
        if (answers.values().stream().anyMatch(answer -> answer == null)) {
            throw new IllegalArgumentException("answers must not contain null values");
        }
        if (usage == null) {
            throw new IllegalArgumentException("usage must not be null");
        }
        if (latency == null || latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be null or negative");
        }
    }
}
