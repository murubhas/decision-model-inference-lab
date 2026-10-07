package dev.decisionmodel.lab.guardrail.runtime;

import java.util.ArrayList;
import java.util.List;

import dev.decisionmodel.lab.guardrail.ChoiceAnswer;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.ScoreAnswer;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;

public final class HybridConfidencePolicy {

    private final double noulClearLow;
    private final double noulClearHigh;
    private final double minimumDistributionConfidence;

    public HybridConfidencePolicy(
            double noulClearLow,
            double noulClearHigh,
            double minimumDistributionConfidence) {
        if (!isProbability(noulClearLow)
                || !isProbability(noulClearHigh)
                || !isProbability(minimumDistributionConfidence)) {
            throw new IllegalArgumentException("hybrid thresholds must be between 0 and 1");
        }
        if (noulClearLow >= 0.5 || noulClearHigh <= 0.5 || noulClearLow >= noulClearHigh) {
            throw new IllegalArgumentException("Noul clear thresholds must surround 0.5");
        }
        this.noulClearLow = noulClearLow;
        this.noulClearHigh = noulClearHigh;
        this.minimumDistributionConfidence = minimumDistributionConfidence;
    }

    public HybridAssessment assess(GuardrailEvaluation evaluation) {
        return assess(evaluation, HybridDecisionProvider.JEV);
    }

    public HybridAssessment assess(
            GuardrailEvaluation evaluation,
            HybridDecisionProvider provider) {
        if (evaluation == null) {
            throw new IllegalArgumentException("evaluation must not be null");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }

        List<String> uncertain = new ArrayList<>();
        evaluation.answers().forEach((id, answer) -> {
            if (isUncertain(answer)) {
                uncertain.add(id);
            }
        });
        return uncertain.isEmpty()
                ? HybridAssessment.clear(provider, List.of())
                : HybridAssessment.uncertain(provider, uncertain, uncertain);
    }

    private boolean isUncertain(SemanticAnswer answer) {
        if (answer instanceof NoulAnswer noul) {
            return noul.probabilityTrue() > noulClearLow
                    && noul.probabilityTrue() < noulClearHigh;
        }
        if (answer instanceof ChoiceAnswer choice) {
            return choice.confidence() < minimumDistributionConfidence;
        }
        if (answer instanceof ScoreAnswer score) {
            return score.confidence() < minimumDistributionConfidence;
        }
        throw new IllegalArgumentException("Unsupported answer type: " + answer.getClass().getName());
    }

    private static boolean isProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
