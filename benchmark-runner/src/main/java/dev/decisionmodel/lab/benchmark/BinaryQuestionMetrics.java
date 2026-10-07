package dev.decisionmodel.lab.benchmark;

public record BinaryQuestionMetrics(
        int samples,
        int truePositives,
        int trueNegatives,
        int falsePositives,
        int falseNegatives,
        double accuracy,
        Double precision,
        Double recall,
        Double falsePositiveRate,
        Double falseNegativeRate,
        double brierScore) {
}
