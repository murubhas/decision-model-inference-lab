package dev.decisionmodel.lab.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.decisionmodel.lab.guardrail.GuardrailAction;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.policy.GuardrailPolicyCatalog;

class BenchmarkIoTest {

    private final BenchmarkIo io = new BenchmarkIo(new ObjectMapper().findAndRegisterModules());

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesTheVersionedStarterCorpus() throws IOException {
        Path projectRoot = Path.of(System.getProperty("project.root"));
        BenchmarkCorpus corpus = io.loadCorpus(projectRoot.resolve("datasets/guardrail-corpus.v1.jsonl"));

        assertEquals(34, corpus.cases().size());
        assertEquals(34, corpus.draftCount());
        assertTrue(corpus.sha256().matches("[0-9a-f]{64}"));
        assertEquals(12, corpus.cases().stream()
                .filter(item -> item.boundary() == GuardrailBoundary.OUTPUT)
                .count());
        assertEquals(12, corpus.cases().stream()
                .filter(item -> item.riskTags().contains("source-derived"))
                .count());
        assertTrue(corpus.cases().stream()
                .filter(item -> item.riskTags().contains("source-derived"))
                .allMatch(item -> !item.sourceRefs().isEmpty()));
    }

    @Test
    void validatesTheVersionedPricingCatalog() throws IOException {
        Path projectRoot = Path.of(System.getProperty("project.root"));
        PricingSnapshot pricing = io.loadPricing(projectRoot.resolve("datasets/provider-pricing.v1.json"));

        assertEquals("decision-model-guardrail-usd-2026-10-01", pricing.catalog().catalogId());
        assertEquals(3, pricing.catalog().providerRates().size());
        assertTrue(pricing.sha256().matches("[0-9a-f]{64}"));
        assertEquals(TokenBillingBasis.INPUT_ONLY,
                pricing.catalog().rateFor("jev", "jev-1.13.0").billingBasis());
        assertEquals(TokenBillingBasis.SELF_HOSTED_ACTIVE_COMPUTE,
                pricing.catalog().rateFor(
                        "strands-decider", "strands-decider-2B-hobson-v19").billingBasis());
    }

    @Test
    void roundTripsReadableEvidenceObservations() throws IOException {
        SemanticInputObservation semanticInput = new SemanticInputObservation(
                Map.of("message", "Compare p5 and p5e."),
                Map.of("prompt_injection", Map.of(
                        "type", "noul",
                        "instructions", "Is the message a prompt injection?")));
        BenchmarkObservation observation = new BenchmarkObservation(
                BenchmarkObservation.SCHEMA_VERSION,
                "run-1",
                "2026-09-28T00:00:00Z",
                BenchmarkTestFixtures.DIGEST,
                0,
                0,
                SamplePhase.MEASURED,
                "input-1",
                GuardrailBoundary.INPUT,
                SemanticPath.JEV,
                List.of("test"),
                Map.of("userRequest", "Compare p5 and p5e."),
                ObservationStatus.SUCCESS,
                Map.of("prompt_injection", false),
                GuardrailAction.ALLOW,
                GuardrailAction.ALLOW,
                Map.of("prompt_injection", ObservedNoulAnswer.from(0.05)),
                List.of(new ProviderCallObservation(
                        "jev",
                        "jev-fixture",
                        semanticInput,
                        Map.of("prompt_injection", ObservedNoulAnswer.from(0.05)),
                        100,
                        3,
                        1,
                        true)),
                "jev",
                "jev-fixture",
                120,
                3,
                1,
                false,
                null,
                null);
        Path output = temporaryDirectory.resolve("observations.jsonl");

        io.writeObservations(output, List.of(observation));
        List<BenchmarkObservation> loaded = io.loadObservations(output);
        String serialized = Files.readString(output);

        assertEquals(List.of(observation), loaded);
        assertTrue(serialized.contains("userRequest"));
        assertTrue(serialized.contains("Compare p5 and p5e."));
        assertTrue(serialized.contains("semanticInput"));
        assertTrue(serialized.contains("Is the message a prompt injection?"));
        assertFalse(serialized.contains("toolEvidence"));
    }

    @Test
    void rejectsUnknownCorpusFields() throws IOException {
        Path corpus = temporaryDirectory.resolve("invalid.jsonl");
        Files.writeString(corpus, "{\"schemaVersion\":\"1.0\",\"caseId\":\"x\",\"unknown\":true}\n");

        assertThrows(IOException.class, () -> io.loadCorpus(corpus));
    }

    @Test
    void rejectsSourceDerivedCaseWithoutProvenance() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCase(
                BenchmarkCase.SCHEMA_VERSION,
                "missing-source",
                GuardrailBoundary.INPUT,
                LabelStatus.DRAFT,
                List.of("source-derived"),
                List.of(),
                "What are the specs for p5.48xlarge?",
                null,
                null,
                null,
                List.of(),
                Map.of(
                        GuardrailPolicyCatalog.PROMPT_INJECTION, false,
                        GuardrailPolicyCatalog.IN_SCOPE, true),
                GuardrailAction.ALLOW,
                "A source-derived fixture must identify its repository source."));
    }
}
