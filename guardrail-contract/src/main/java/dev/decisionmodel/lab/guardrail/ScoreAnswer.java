package dev.decisionmodel.lab.guardrail;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ScoreAnswer(
        double score,
        Map<Integer, String> legend,
        Map<Integer, Double> probabilities,
        double confidence) implements SemanticAnswer {

    public ScoreAnswer {
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        legend = copyLegend(legend);
        probabilities = copyProbabilities(probabilities);
        confidence = ContractValidation.requireProbability(confidence, "confidence");
        if (!legend.keySet().equals(probabilities.keySet())) {
            throw new IllegalArgumentException("legend and probabilities must have identical levels");
        }
        int minimum = Collections.min(legend.keySet());
        int maximum = Collections.max(legend.keySet());
        if (score < minimum || score > maximum) {
            throw new IllegalArgumentException("score must be within the legend range");
        }
    }

    @Override
    public QuestionType type() {
        return QuestionType.SCORE;
    }

    private static Map<Integer, String> copyLegend(Map<Integer, String> source) {
        Map<Integer, String> values = ContractValidation.orderedCopy(source, "legend");
        if (values.size() < 2) {
            throw new IllegalArgumentException("legend must contain at least 2 levels");
        }
        for (Map.Entry<Integer, String> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("legend levels must not be null");
            }
            ContractValidation.requireText(entry.getValue(), "legend description");
        }
        return values;
    }

    private static Map<Integer, Double> copyProbabilities(Map<Integer, Double> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("probabilities must not be empty");
        }
        Map<Integer, Double> values = new LinkedHashMap<>();
        double sum = 0.0;
        for (Map.Entry<Integer, Double> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("probability levels and values must not be null");
            }
            double probability = ContractValidation.requireProbability(entry.getValue(), "probability");
            values.put(entry.getKey(), probability);
            sum += probability;
        }
        if (Math.abs(sum - 1.0) > 0.01) {
            throw new IllegalArgumentException("probabilities must sum to 1 within 0.01");
        }
        return Collections.unmodifiableMap(values);
    }
}
