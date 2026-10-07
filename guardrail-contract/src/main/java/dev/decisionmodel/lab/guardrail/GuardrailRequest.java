package dev.decisionmodel.lab.guardrail;

import java.util.Map;

public record GuardrailRequest(
        String correlationId,
        GuardrailBoundary boundary,
        Map<String, Object> state,
        Map<String, SemanticQuestion> questions) {

    public GuardrailRequest {
        correlationId = ContractValidation.requireText(correlationId, "correlationId");
        if (boundary == null) {
            throw new IllegalArgumentException("boundary must not be null");
        }
        state = ContractValidation.orderedCopy(state, "state");
        if (state.isEmpty()) {
            throw new IllegalArgumentException("state must not be empty");
        }
        questions = ContractValidation.orderedCopy(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
        for (Map.Entry<String, SemanticQuestion> entry : questions.entrySet()) {
            ContractValidation.requireText(entry.getKey(), "question id");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("questions must not contain null values");
            }
        }
    }
}
