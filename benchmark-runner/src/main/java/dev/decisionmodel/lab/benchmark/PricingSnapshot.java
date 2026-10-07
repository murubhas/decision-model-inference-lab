package dev.decisionmodel.lab.benchmark;

public record PricingSnapshot(PricingCatalog catalog, String sha256) {

    public PricingSnapshot {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
        }
    }
}
