package dev.decisionmodel.lab.guardrail;

public record NoulQuestion(
        String instructions,
        String trueCriteria,
        String falseCriteria) implements SemanticQuestion {

    public NoulQuestion {
        instructions = ContractValidation.requireText(instructions, "instructions");
    }

    public NoulQuestion(String instructions) {
        this(instructions, null, null);
    }

    @Override
    public QuestionType type() {
        return QuestionType.NOUL;
    }
}
