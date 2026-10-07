package dev.decisionmodel.lab.guardrail;

import java.util.List;

public record ScoreQuestion(
        String instructions,
        List<String> levels) implements SemanticQuestion {

    private static final int MAX_LEVELS = 10;

    public ScoreQuestion {
        instructions = ContractValidation.requireText(instructions, "instructions");
        if (levels == null) {
            throw new IllegalArgumentException("levels must not be null");
        }
        levels = List.copyOf(levels);
        if (levels.size() < 2 || levels.size() > MAX_LEVELS) {
            throw new IllegalArgumentException("levels must contain between 2 and " + MAX_LEVELS + " entries");
        }
        for (String level : levels) {
            ContractValidation.requireText(level, "level");
        }
    }

    @Override
    public QuestionType type() {
        return QuestionType.SCORE;
    }
}
