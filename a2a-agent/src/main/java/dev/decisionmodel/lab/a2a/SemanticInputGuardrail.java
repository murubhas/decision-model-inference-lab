package dev.decisionmodel.lab.a2a;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathResult;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.decisionmodel.lab.policy.NoulDecisionPolicy;
import dev.decisionmodel.lab.policy.PolicyDecision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SemanticInputGuardrail implements InputGuardrailEvaluator {

    private final SemanticPathRunner runner;
    private final SemanticPath path;
    private final NoulDecisionPolicy policy;

    @Inject
    public SemanticInputGuardrail(
            SemanticPathRunner runner,
            @ConfigProperty(name = "guardrail.semantic.mode", defaultValue = "HYBRID") SemanticPath path,
            @ConfigProperty(
                    name = "guardrail.input.prompt-injection-escalate-threshold",
                    defaultValue = "0.35") double injectionEscalateThreshold,
            @ConfigProperty(
                    name = "guardrail.input.prompt-injection-block-threshold",
                    defaultValue = "0.80") double injectionBlockThreshold,
            @ConfigProperty(
                    name = "guardrail.input.in-scope-block-threshold",
                    defaultValue = "0.20") double inScopeBlockThreshold,
            @ConfigProperty(
                    name = "guardrail.input.in-scope-allow-threshold",
                    defaultValue = "0.80") double inScopeAllowThreshold) {
        this.runner = runner;
        this.path = path;
        this.policy = GuardrailPolicyCatalog.inputPolicy(
                injectionEscalateThreshold,
                injectionBlockThreshold,
                inScopeBlockThreshold,
                inScopeAllowThreshold);
    }

    @Override
    public InputGuardrailResult evaluate(String correlationId, String userInput) {
        SemanticPathResult pathResult = runner.run(
                path,
                GuardrailPolicyCatalog.inputRequest(correlationId, userInput));
        PolicyDecision decision = policy.decide(pathResult.selectedEvaluation());
        return new InputGuardrailResult(decision.action(), decision.reasons(), pathResult);
    }
}
