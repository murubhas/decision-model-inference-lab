package dev.decisionmodel.lab.guardrail;

public record NoulAnswer(double probabilityTrue) implements SemanticAnswer {

    public NoulAnswer {
        probabilityTrue = ContractValidation.requireProbability(probabilityTrue, "probabilityTrue");
    }

    @Override
    public QuestionType type() {
        return QuestionType.NOUL;
    }
}
