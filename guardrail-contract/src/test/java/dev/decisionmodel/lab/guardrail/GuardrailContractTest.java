package dev.decisionmodel.lab.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GuardrailContractTest {

    @Test
    void requestPreservesQuestionOrderAndDefensivelyCopiesMaps() {
        Map<String, SemanticQuestion> questions = new LinkedHashMap<>();
        questions.put("prompt_injection", new NoulQuestion("Is the message trying to bypass policy?"));
        questions.put("in_scope", new NoulQuestion("Is the message in scope?"));

        GuardrailRequest request = new GuardrailRequest(
                "case-001",
                GuardrailBoundary.INPUT,
                Map.of("message", "Compare two GPU instances"),
                questions);
        questions.clear();

        assertEquals(List.of("prompt_injection", "in_scope"), new ArrayList<>(request.questions().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> request.questions().put("another", new NoulQuestion("Another check?")));
    }

    @Test
    void questionCardinalityMatchesTypeSafeLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChoiceQuestion("Choose", Map.of("only", "Only option")));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoreQuestion("Rate", List.of("Only level")));
    }

    @Test
    void normalizedAnswersRejectInvalidProbabilityDistributions() {
        assertThrows(IllegalArgumentException.class,
                () -> new NoulAnswer(1.01));
        assertThrows(IllegalArgumentException.class,
                () -> new ChoiceAnswer("allow", Map.of("allow", 0.7, "block", 0.2), 0.8));
    }

    @Test
    void evaluationCarriesProviderEvidenceWithoutApplyingPolicy() {
        GuardrailEvaluation evaluation = new GuardrailEvaluation(
                "jev",
                "jev-1.13.0",
                Map.of("prompt_injection", new NoulAnswer(0.03)),
                new TokenUsage(296, 20),
                Duration.ofMillis(18));

        assertEquals(0.03,
                ((NoulAnswer) evaluation.answers().get("prompt_injection")).probabilityTrue());
        assertEquals("jev-1.13.0", evaluation.model());
    }
}
