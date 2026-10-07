package dev.decisionmodel.lab.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.TokenUsage;

class NoulDecisionPolicyTest {

    private final NoulDecisionPolicy policy = new NoulDecisionPolicy(List.of(
            new NoulDecisionRule("risk", NoulRiskDirection.HIGH_TRUE, 0.35, 0.80),
            new NoulDecisionRule("positive", NoulRiskDirection.LOW_TRUE, 0.80, 0.20)));

    @Test
    void allowsClearAnswers() {
        assertEquals(GuardrailAction.ALLOW, policy.decide(evaluation(0.10, 0.90)).action());
    }

    @Test
    void escalatesEitherUncertainDirection() {
        assertEquals(GuardrailAction.ESCALATE, policy.decide(evaluation(0.35, 0.90)).action());
        assertEquals(GuardrailAction.ESCALATE, policy.decide(evaluation(0.10, 0.79)).action());
    }

    @Test
    void blockTakesPriority() {
        PolicyDecision decision = policy.decide(evaluation(0.80, 0.50));
        assertEquals(GuardrailAction.BLOCK, decision.action());
        assertEquals(List.of("risk:block", "positive:uncertain"), decision.reasons());
    }

    @Test
    void rejectsMissingOrWrongAnswer() {
        GuardrailEvaluation incomplete = new GuardrailEvaluation(
                "fixture",
                "fixture-v1",
                Map.of("risk", new NoulAnswer(0.1)),
                new TokenUsage(1, 1),
                Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> policy.decide(incomplete));
    }

    private static GuardrailEvaluation evaluation(double risk, double positive) {
        return new GuardrailEvaluation(
                "fixture",
                "fixture-v1",
                Map.of("risk", new NoulAnswer(risk), "positive", new NoulAnswer(positive)),
                new TokenUsage(1, 1),
                Duration.ZERO);
    }
}
