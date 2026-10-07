package dev.decisionmodel.lab.llm;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

final class LlmDecisionDtos {

    private LlmDecisionDtos() {
    }

    record Response(Map<String, Answer> answers) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = NoulAnswer.class, name = "noul"),
            @JsonSubTypes.Type(value = ChoiceAnswer.class, name = "choice"),
            @JsonSubTypes.Type(value = ScoreAnswer.class, name = "score")
    })
    sealed interface Answer permits NoulAnswer, ChoiceAnswer, ScoreAnswer {
    }

    @JsonTypeName("noul")
    record NoulAnswer(@JsonProperty("probability_true") double probabilityTrue) implements Answer {
    }

    @JsonTypeName("choice")
    record ChoiceAnswer(
            String selected,
            Map<String, Double> probabilities,
            double confidence) implements Answer {
    }

    @JsonTypeName("score")
    record ScoreAnswer(
            double score,
            Map<Integer, Double> probabilities,
            double confidence) implements Answer {
    }
}
