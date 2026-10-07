package dev.decisionmodel.lab.benchmark;

import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.decisionmodel.lab.policy.NoulDecisionPolicy;

public record BenchmarkThresholds(
        double injectionEscalate,
        double injectionBlock,
        double inScopeBlock,
        double inScopeAllow,
        double toolPositiveBlock,
        double toolPositiveAllow,
        double outputRiskEscalate,
        double outputRiskBlock,
        double outputPositiveBlock,
        double outputPositiveAllow) {

    public BenchmarkThresholds {
        GuardrailPolicyCatalog.inputPolicy(
                injectionEscalate, injectionBlock, inScopeBlock, inScopeAllow);
        GuardrailPolicyCatalog.toolCallPolicy(toolPositiveBlock, toolPositiveAllow);
        GuardrailPolicyCatalog.outputPolicy(
                outputRiskEscalate,
                outputRiskBlock,
                outputPositiveBlock,
                outputPositiveAllow);
    }

    public NoulDecisionPolicy policy(GuardrailBoundary boundary) {
        return switch (boundary) {
            case INPUT -> GuardrailPolicyCatalog.inputPolicy(
                    injectionEscalate, injectionBlock, inScopeBlock, inScopeAllow);
            case TOOL_CALL -> GuardrailPolicyCatalog.toolCallPolicy(
                    toolPositiveBlock, toolPositiveAllow);
            case OUTPUT -> GuardrailPolicyCatalog.outputPolicy(
                    outputRiskEscalate,
                    outputRiskBlock,
                    outputPositiveBlock,
                    outputPositiveAllow);
            case TOOL_RESULT -> throw new IllegalArgumentException(
                    "TOOL_RESULT is evaluated through the OUTPUT grounding policy");
        };
    }

    public static BenchmarkThresholds defaults() {
        return new BenchmarkThresholds(
                0.35, 0.80, 0.20, 0.80,
                0.20, 0.80,
                0.35, 0.80, 0.20, 0.80);
    }
}
