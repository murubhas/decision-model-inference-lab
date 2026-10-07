package dev.decisionmodel.lab.a2a;

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
public class SemanticRuntimeProducer {

    @Produces
    @ApplicationScoped
    public SemanticPathRunner semanticPathRunner(
            BedrockSemanticGuardrailProvider llmProvider,
            JevSemanticGuardrailProvider jevProvider,
            StrandsDeciderSemanticGuardrailProvider deciderProvider,
            @ConfigProperty(name = "guardrail.hybrid.noul-clear-low", defaultValue = "0.20") double noulClearLow,
            @ConfigProperty(name = "guardrail.hybrid.noul-clear-high", defaultValue = "0.80") double noulClearHigh,
            @ConfigProperty(
                    name = "guardrail.hybrid.minimum-distribution-confidence",
                    defaultValue = "0.65") double minimumDistributionConfidence,
            @ConfigProperty(
                    name = "guardrail.hybrid.availability-policy",
                    defaultValue = "FALLBACK_TO_LLM") HybridAvailabilityPolicy availabilityPolicy) {
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
}
