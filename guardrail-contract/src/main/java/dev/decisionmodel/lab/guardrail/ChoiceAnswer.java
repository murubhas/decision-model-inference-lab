package dev.decisionmodel.lab.guardrail;

import java.util.Map;

public record ChoiceAnswer(
        String selected,
        Map<String, Double> probabilities,
        double confidence) implements SemanticAnswer {

    public ChoiceAnswer {
        selected = ContractValidation.requireText(selected, "selected");
        probabilities = ContractValidation.probabilityDistribution(probabilities, "probabilities");
        confidence = ContractValidation.requireProbability(confidence, "confidence");
        if (!probabilities.containsKey(selected)) {
            throw new IllegalArgumentException("selected must identify an entry in probabilities");
        }
    }

    @Override
    public QuestionType type() {
        return QuestionType.CHOICE;
    }
}
