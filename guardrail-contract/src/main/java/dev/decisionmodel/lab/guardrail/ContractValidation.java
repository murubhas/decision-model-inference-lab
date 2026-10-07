package dev.decisionmodel.lab.guardrail;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ContractValidation {

    private static final double DISTRIBUTION_TOLERANCE = 0.01;

    private ContractValidation() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static double requireProbability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }

    static <K, V> Map<K, V> orderedCopy(Map<K, V> source, String field) {
        if (source == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static Map<String, Double> probabilityDistribution(Map<String, Double> source, String field) {
        Map<String, Double> values = orderedCopy(source, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }

        double sum = 0.0;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            requireText(entry.getKey(), field + " key");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(field + " values must not be null");
            }
            sum += requireProbability(entry.getValue(), field + " value");
        }
        if (Math.abs(sum - 1.0) > DISTRIBUTION_TOLERANCE) {
            throw new IllegalArgumentException(field + " must sum to 1 within " + DISTRIBUTION_TOLERANCE);
        }
        return values;
    }
}
