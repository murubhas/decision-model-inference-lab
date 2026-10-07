package dev.decisionmodel.lab.guardrail.runtime;

import java.util.List;
import java.util.Optional;

import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;

public record SemanticPathResult(
        SemanticPath path,
        List<GuardrailEvaluation> evaluations,
        GuardrailEvaluation selectedEvaluation,
        List<Integer> contributingEvaluationIndexes,
        Optional<HybridAssessment> hybridAssessment) {

    public SemanticPathResult {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (evaluations == null || evaluations.isEmpty()) {
            throw new IllegalArgumentException("evaluations must not be empty");
        }
        evaluations = List.copyOf(evaluations);
        if (selectedEvaluation == null) {
            throw new IllegalArgumentException("selectedEvaluation must not be null");
        }
        if (contributingEvaluationIndexes == null || contributingEvaluationIndexes.isEmpty()) {
            throw new IllegalArgumentException("contributingEvaluationIndexes must not be empty");
        }
        contributingEvaluationIndexes = List.copyOf(contributingEvaluationIndexes);
        if (contributingEvaluationIndexes.stream().distinct().count() != contributingEvaluationIndexes.size()) {
            throw new IllegalArgumentException("contributingEvaluationIndexes must not contain duplicates");
        }
        for (int index : contributingEvaluationIndexes) {
            if (index < 0 || index >= evaluations.size()) {
                throw new IllegalArgumentException("contributingEvaluationIndexes contains an invalid index");
            }
        }
        if (hybridAssessment == null) {
            throw new IllegalArgumentException("hybridAssessment must not be null");
        }
        if (path.isHybrid() && hybridAssessment.isEmpty()) {
            throw new IllegalArgumentException("hybrid results require an assessment");
        }
        if (!path.isHybrid() && hybridAssessment.isPresent()) {
            throw new IllegalArgumentException("direct paths must not include a hybrid assessment");
        }
        if (!path.isHybrid()
                && (evaluations.size() != 1
                        || contributingEvaluationIndexes.size() != 1
                        || contributingEvaluationIndexes.getFirst() != 0
                        || !selectedEvaluation.equals(evaluations.getFirst()))) {
            throw new IllegalArgumentException("direct paths require one selected provider evaluation");
        }
    }

    public SemanticPathResult(
            SemanticPath path,
            List<GuardrailEvaluation> evaluations,
            int selectedEvaluationIndex,
            Optional<HybridAssessment> hybridAssessment) {
        this(
                path,
                evaluations,
                selected(evaluations, selectedEvaluationIndex),
                List.of(selectedEvaluationIndex),
                hybridAssessment);
    }

    public boolean contributes(int evaluationIndex) {
        return contributingEvaluationIndexes.contains(evaluationIndex);
    }

    private static GuardrailEvaluation selected(
            List<GuardrailEvaluation> evaluations,
            int selectedEvaluationIndex) {
        if (evaluations == null
                || selectedEvaluationIndex < 0
                || selectedEvaluationIndex >= evaluations.size()) {
            throw new IllegalArgumentException("selectedEvaluationIndex is outside evaluations");
        }
        return evaluations.get(selectedEvaluationIndex);
    }
}
