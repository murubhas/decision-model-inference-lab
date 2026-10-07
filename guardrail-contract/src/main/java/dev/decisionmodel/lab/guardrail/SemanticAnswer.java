package dev.decisionmodel.lab.guardrail;

public sealed interface SemanticAnswer permits NoulAnswer, ChoiceAnswer, ScoreAnswer {

    QuestionType type();
}
