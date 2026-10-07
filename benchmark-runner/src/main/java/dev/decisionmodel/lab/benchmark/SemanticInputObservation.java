package dev.decisionmodel.lab.benchmark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.decisionmodel.lab.guardrail.ChoiceQuestion;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulQuestion;
import dev.decisionmodel.lab.guardrail.ScoreQuestion;
import dev.decisionmodel.lab.guardrail.SemanticQuestion;

public record SemanticInputObservation(
        Map<String, Object> state,
        Map<String, Map<String, Object>> questions) {

    public SemanticInputObservation {
        if (state == null || state.isEmpty() || questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("state and questions must not be empty");
        }
        state = Collections.unmodifiableMap(new LinkedHashMap<>(state));
        LinkedHashMap<String, Map<String, Object>> copiedQuestions = new LinkedHashMap<>();
        questions.forEach((id, question) -> copiedQuestions.put(
                id,
                Collections.unmodifiableMap(new LinkedHashMap<>(question))));
        questions = Collections.unmodifiableMap(copiedQuestions);
    }

    public static SemanticInputObservation from(GuardrailRequest request) {
        LinkedHashMap<String, Map<String, Object>> questions = new LinkedHashMap<>();
        request.questions().forEach((id, question) -> questions.put(id, question(question)));
        return new SemanticInputObservation(request.state(), questions);
    }

    private static Map<String, Object> question(SemanticQuestion question) {
        LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("type", question.type().name().toLowerCase(Locale.ROOT));
        mapped.put("instructions", question.instructions());
        if (question instanceof NoulQuestion noul) {
            LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
            if (noul.trueCriteria() != null) {
                criteria.put("true", noul.trueCriteria());
            }
            if (noul.falseCriteria() != null) {
                criteria.put("false", noul.falseCriteria());
            }
            if (!criteria.isEmpty()) {
                mapped.put("criteria", criteria);
            }
        } else if (question instanceof ChoiceQuestion choice) {
            mapped.put("options", choice.options());
        } else if (question instanceof ScoreQuestion score) {
            mapped.put("levels", List.copyOf(score.levels()));
        } else {
            throw new IllegalArgumentException("unsupported semantic question: " + question.getClass().getName());
        }
        return mapped;
    }
}
