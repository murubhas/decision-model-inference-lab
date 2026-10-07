package dev.decisionmodel.lab.benchmark;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PricingCatalog(
        String schemaVersion,
        String catalogId,
        String checkedAt,
        String currency,
        List<ProviderPricing> providerRates,
        List<String> caveats) {

    public static final String SCHEMA_VERSION = "1.0";

    public PricingCatalog {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported pricing schemaVersion: " + schemaVersion);
        }
        requireText(catalogId, "catalogId");
        requireText(checkedAt, "checkedAt");
        requireText(currency, "currency");
        Instant.parse(checkedAt);
        if (providerRates == null || providerRates.isEmpty()) {
            throw new IllegalArgumentException("providerRates must not be empty");
        }
        providerRates = List.copyOf(providerRates);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);

        Set<String> identities = new HashSet<>();
        for (ProviderPricing rate : providerRates) {
            String identity = rate.provider() + "\u0000" + rate.modelContains();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate provider pricing: " + rate.provider());
            }
        }
    }

    ProviderPricing rateFor(String provider, String model) {
        List<ProviderPricing> matches = providerRates.stream()
                .filter(rate -> rate.matches(provider, model))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "expected exactly one price for provider=" + provider + ", model=" + model
                            + ", matches=" + matches.size());
        }
        return matches.getFirst();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
