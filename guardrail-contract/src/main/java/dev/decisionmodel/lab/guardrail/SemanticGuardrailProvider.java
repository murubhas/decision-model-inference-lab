package dev.decisionmodel.lab.guardrail;

public interface SemanticGuardrailProvider {

    String name();

    GuardrailEvaluation evaluate(GuardrailRequest request);
}
