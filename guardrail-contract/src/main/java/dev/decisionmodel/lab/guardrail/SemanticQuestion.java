package dev.decisionmodel.lab.guardrail;

public sealed interface SemanticQuestion permits NoulQuestion, ChoiceQuestion, ScoreQuestion {

    QuestionType type();

    String instructions();
}
