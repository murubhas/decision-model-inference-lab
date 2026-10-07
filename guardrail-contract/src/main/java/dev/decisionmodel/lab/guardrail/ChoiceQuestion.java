package dev.decisionmodel.lab.guardrail;

import java.util.Map;

public record ChoiceQuestion(
        String instructions,
        Map<String, String> options) implements SemanticQuestion {

    private static final int MAX_OPTIONS = 255;

    public ChoiceQuestion {
        instructions = ContractValidation.requireText(instructions, "instructions");
        options = ContractValidation.orderedCopy(options, "options");
        if (options.size() < 2 || options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("options must contain between 2 and " + MAX_OPTIONS + " entries");
        }
        for (String option : options.keySet()) {
            ContractValidation.requireText(option, "option");
        }
    }

    @Override
    public QuestionType type() {
        return QuestionType.CHOICE;
    }
}
