package dev.decisionmodel.lab.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.NoulAnswer;

public final class NoulDecisionPolicy {

    private final List<NoulDecisionRule> rules;

    public NoulDecisionPolicy(List<NoulDecisionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
        this.rules = List.copyOf(rules);
        Set<String> ids = new HashSet<>();
        for (NoulDecisionRule rule : this.rules) {
            if (rule == null) {
                throw new IllegalArgumentException("rules must not contain null values");
            }
            if (!ids.add(rule.questionId())) {
                throw new IllegalArgumentException("duplicate rule for question '" + rule.questionId() + "'");
            }
        }
    }

    public PolicyDecision decide(GuardrailEvaluation evaluation) {
        if (evaluation == null) {
            throw new IllegalArgumentException("evaluation must not be null");
        }

        List<String> reasons = new ArrayList<>();
        boolean block = false;
        boolean escalate = false;
        for (NoulDecisionRule rule : rules) {
            GuardrailAction action = rule.classify(answer(evaluation, rule.questionId()).probabilityTrue());
            if (action == GuardrailAction.BLOCK) {
                reasons.add(rule.questionId() + ":block");
                block = true;
            } else if (action == GuardrailAction.ESCALATE) {
                reasons.add(rule.questionId() + ":uncertain");
                escalate = true;
            }
        }
        if (block) {
            return new PolicyDecision(GuardrailAction.BLOCK, reasons);
        }
        if (escalate) {
            return new PolicyDecision(GuardrailAction.ESCALATE, reasons);
        }
        return new PolicyDecision(GuardrailAction.ALLOW, List.of("semantic_checks:clear"));
    }

    public List<NoulDecisionRule> rules() {
        return rules;
    }

    private static NoulAnswer answer(GuardrailEvaluation evaluation, String questionId) {
        if (evaluation.answers().get(questionId) instanceof NoulAnswer answer) {
            return answer;
        }
        throw new IllegalArgumentException("missing Noul answer for question '" + questionId + "'");
    }
}
