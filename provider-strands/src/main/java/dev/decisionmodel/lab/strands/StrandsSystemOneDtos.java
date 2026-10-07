package dev.decisionmodel.lab.strands;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

public final class StrandsSystemOneDtos {

    private StrandsSystemOneDtos() {
    }

    public record Request(
            Map<String, Object> state,
            String model,
            Map<String, Question> questions) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = NoulQuestion.class, name = "noul"),
            @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice"),
            @JsonSubTypes.Type(value = ScoreQuestion.class, name = "score")
    })
    public sealed interface Question permits NoulQuestion, ChoiceQuestion, ScoreQuestion {
    }

    @JsonTypeName("noul")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoulQuestion(String instructions, NoulCriteria criteria) implements Question {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoulCriteria(
            @JsonProperty("true") String whenTrue,
            @JsonProperty("false") String whenFalse) {
    }

    @JsonTypeName("choice")
    public record ChoiceQuestion(String instructions, Map<String, String> criteria) implements Question {
    }

    @JsonTypeName("score")
    public record ScoreQuestion(String instructions, List<String> criteria) implements Question {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            String model,
            Map<String, Answer> answers,
            Usage usage) {
    }

    public record Usage(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = NoulAnswer.class, name = "noul"),
            @JsonSubTypes.Type(value = ChoiceAnswer.class, name = "choice"),
            @JsonSubTypes.Type(value = ScoreAnswer.class, name = "score")
    })
    public sealed interface Answer permits NoulAnswer, ChoiceAnswer, ScoreAnswer {
    }

    @JsonTypeName("noul")
    public record NoulAnswer(double noul) implements Answer {
    }

    @JsonTypeName("choice")
    public record ChoiceAnswer(
            String choice,
            Map<String, Double> probabilities,
            double confidence) implements Answer {
    }

    @JsonTypeName("score")
    public record ScoreAnswer(
            double score,
            Map<String, String> legend,
            Map<String, Double> probabilities,
            double confidence) implements Answer {
    }
}
