package dev.decisionmodel.lab.benchmark;

import java.math.BigDecimal;
import java.net.URI;

public record ProviderPricing(
        String provider,
        String modelContains,
        ProviderKind providerKind,
        TokenBillingBasis billingBasis,
        BigDecimal inputUsdPerMillionTokens,
        BigDecimal outputUsdPerMillionTokens,
        BigDecimal computeUsdPerHour,
        String sourceUrl,
        String sourcePublishedAt) {

    public ProviderPricing {
        requireText(provider, "provider");
        requireText(modelContains, "modelContains");
        requireText(sourceUrl, "sourceUrl");
        if (providerKind == null || billingBasis == null) {
            throw new IllegalArgumentException("providerKind and billingBasis are required");
        }
        if (billingBasis == TokenBillingBasis.SELF_HOSTED_ACTIVE_COMPUTE) {
            if (inputUsdPerMillionTokens != null || outputUsdPerMillionTokens != null) {
                throw new IllegalArgumentException(
                        "SELF_HOSTED_ACTIVE_COMPUTE pricing must not define token rates");
            }
            requireNonNegative(computeUsdPerHour, "computeUsdPerHour");
        } else {
            requireNonNegative(inputUsdPerMillionTokens, "inputUsdPerMillionTokens");
            if (billingBasis == TokenBillingBasis.INPUT_AND_OUTPUT) {
                requireNonNegative(outputUsdPerMillionTokens, "outputUsdPerMillionTokens");
            } else if (outputUsdPerMillionTokens != null) {
                throw new IllegalArgumentException(
                        "INPUT_ONLY pricing must not define an output-token rate");
            }
            if (computeUsdPerHour != null) {
                throw new IllegalArgumentException(
                        "token pricing must not define computeUsdPerHour");
            }
        }
        URI.create(sourceUrl);
    }

    public ProviderPricing(
            String provider,
            String modelContains,
            ProviderKind providerKind,
            TokenBillingBasis billingBasis,
            BigDecimal inputUsdPerMillionTokens,
            BigDecimal outputUsdPerMillionTokens,
            String sourceUrl,
            String sourcePublishedAt) {
        this(
                provider,
                modelContains,
                providerKind,
                billingBasis,
                inputUsdPerMillionTokens,
                outputUsdPerMillionTokens,
                null,
                sourceUrl,
                sourcePublishedAt);
    }

    boolean matches(String observedProvider, String observedModel) {
        return provider.equals(observedProvider) && observedModel.contains(modelContains);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
