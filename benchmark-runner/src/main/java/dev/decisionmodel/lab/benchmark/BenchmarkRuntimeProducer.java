package dev.decisionmodel.lab.benchmark;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.runtime.HybridAvailabilityPolicy;
import dev.decisionmodel.lab.guardrail.runtime.HybridConfidencePolicy;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPathRunner;
import dev.decisionmodel.lab.llm.BedrockSemanticGuardrailProvider;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;
import dev.decisionmodel.lab.strands.StrandsDeciderSemanticGuardrailProvider;
import dev.decisionmodel.lab.typesafe.JevSemanticGuardrailProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class BenchmarkRuntimeProducer {

    @Produces
    @ApplicationScoped
    SemanticPathRunner semanticPathRunner(
            BedrockSemanticGuardrailProvider llmProvider,
            JevSemanticGuardrailProvider jevProvider,
            StrandsDeciderSemanticGuardrailProvider deciderProvider,
            @ConfigProperty(name = "guardrail.hybrid.noul-clear-low") double noulClearLow,
            @ConfigProperty(name = "guardrail.hybrid.noul-clear-high") double noulClearHigh,
            @ConfigProperty(name = "guardrail.hybrid.minimum-distribution-confidence")
                    double minimumDistributionConfidence,
            @ConfigProperty(name = "guardrail.hybrid.availability-policy")
                    HybridAvailabilityPolicy availabilityPolicy) {
        return new SemanticPathRunner(
                llmProvider,
                jevProvider,
                deciderProvider,
                new HybridConfidencePolicy(
                        noulClearLow,
                        noulClearHigh,
                        minimumDistributionConfidence),
                GuardrailPolicyCatalog.hybridRoutingPolicy(),
                availabilityPolicy);
    }

    @Produces
    BenchmarkThresholds benchmarkThresholds(
            @ConfigProperty(name = "guardrail.input.prompt-injection-escalate-threshold")
                    double injectionEscalate,
            @ConfigProperty(name = "guardrail.input.prompt-injection-block-threshold")
                    double injectionBlock,
            @ConfigProperty(name = "guardrail.input.in-scope-block-threshold")
                    double inScopeBlock,
            @ConfigProperty(name = "guardrail.input.in-scope-allow-threshold")
                    double inScopeAllow,
            @ConfigProperty(name = "guardrail.tool.positive-block-threshold")
                    double toolPositiveBlock,
            @ConfigProperty(name = "guardrail.tool.positive-allow-threshold")
                    double toolPositiveAllow,
            @ConfigProperty(name = "guardrail.output.risk-escalate-threshold")
                    double outputRiskEscalate,
            @ConfigProperty(name = "guardrail.output.risk-block-threshold")
                    double outputRiskBlock,
            @ConfigProperty(name = "guardrail.output.positive-block-threshold")
                    double outputPositiveBlock,
            @ConfigProperty(name = "guardrail.output.positive-allow-threshold")
                    double outputPositiveAllow) {
        return new BenchmarkThresholds(
                injectionEscalate,
                injectionBlock,
                inScopeBlock,
                inScopeAllow,
                toolPositiveBlock,
                toolPositiveAllow,
                outputRiskEscalate,
                outputRiskBlock,
                outputPositiveBlock,
                outputPositiveAllow);
    }
}
