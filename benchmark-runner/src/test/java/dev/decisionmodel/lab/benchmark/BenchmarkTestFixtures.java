package dev.decisionmodel.lab.benchmark;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.TokenUsage;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;

final class BenchmarkTestFixtures {

    static final String DIGEST = "a".repeat(64);

    private BenchmarkTestFixtures() {
    }

    static BenchmarkCase inputCase(
            String id,
            String userRequest,
            boolean injection,
            boolean inScope,
            GuardrailAction action) {
        return new BenchmarkCase(
                BenchmarkCase.SCHEMA_VERSION,
                id,
                GuardrailBoundary.INPUT,
                LabelStatus.APPROVED,
                List.of("test"),
                List.of(),
                userRequest,
                null,
                null,
                null,
                List.of(),
                Map.of(
                        GuardrailPolicyCatalog.PROMPT_INJECTION, injection,
                        GuardrailPolicyCatalog.IN_SCOPE, inScope),
                action,
                "Test fixture with an independently assigned label.");
    }

    static GuardrailEvaluation inputEvaluation(
            String provider,
            GuardrailRequest request,
            double injection,
            double inScope) {
        Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
        request.questions().keySet().forEach(questionId -> answers.put(
                questionId,
                new NoulAnswer(questionId.equals(GuardrailPolicyCatalog.PROMPT_INJECTION)
                        ? injection
                        : inScope)));
        return new GuardrailEvaluation(
                provider,
                provider + "-model",
                answers,
                new TokenUsage(provider.equals("llm") ? 5 : 3, provider.equals("llm") ? 2 : 1),
                Duration.ofMillis(provider.equals("llm") ? 20 : 5));
    }

    static PricingSnapshot pricing() {
        return new PricingSnapshot(
                new PricingCatalog(
                        PricingCatalog.SCHEMA_VERSION,
                        "test-pricing",
                        "2026-09-28T00:00:00Z",
                        "USD",
                        List.of(
                                new ProviderPricing(
                                        "llm",
                                        "llm-model",
                                        ProviderKind.GENERATIVE_LLM,
                                        TokenBillingBasis.INPUT_AND_OUTPUT,
                                        BigDecimal.valueOf(2),
                                        BigDecimal.valueOf(10),
                                        "https://example.test/llm",
                                        "2026-09-28T00:00:00Z"),
                                new ProviderPricing(
                                        "jev",
                                        "jev-model",
                                        ProviderKind.DECISION_MODEL,
                                        TokenBillingBasis.INPUT_ONLY,
                                        BigDecimal.valueOf(0.042),
                                        null,
                                        "https://example.test/jev",
                                        null),
                                new ProviderPricing(
                                        "strands-decider",
                                        "strands-decider-model",
                                        ProviderKind.DECISION_MODEL,
                                        TokenBillingBasis.SELF_HOSTED_ACTIVE_COMPUTE,
                                        null,
                                        null,
                                        BigDecimal.ONE,
                                        "https://example.test/compute",
                                        "2026-09-28T00:00:00Z")),
                        List.of("test rates")),
                DIGEST);
    }
}
