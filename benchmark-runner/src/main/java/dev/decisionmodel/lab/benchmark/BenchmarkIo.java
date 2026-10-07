package dev.decisionmodel.lab.benchmark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BenchmarkIo {

    private final ObjectReader caseReader;
    private final ObjectReader observationReader;
    private final ObjectReader pricingReader;
    private final ObjectWriter lineWriter;
    private final ObjectWriter reportWriter;

    @Inject
    public BenchmarkIo(ObjectMapper objectMapper) {
        ObjectMapper strict = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.caseReader = strict.readerFor(BenchmarkCase.class);
        this.observationReader = strict.readerFor(BenchmarkObservation.class);
        this.pricingReader = strict.readerFor(PricingCatalog.class);
        this.lineWriter = strict.writer();
        this.reportWriter = strict.writerWithDefaultPrettyPrinter();
    }

    public BenchmarkCorpus loadCorpus(Path path) throws IOException {
        return new BenchmarkCorpus(readJsonLines(path, caseReader, BenchmarkCase.class), digest(path));
    }

    public List<BenchmarkObservation> loadObservations(Path path) throws IOException {
        return readJsonLines(path, observationReader, BenchmarkObservation.class);
    }

    public PricingSnapshot loadPricing(Path path) throws IOException {
        return new PricingSnapshot(pricingReader.readValue(path.toFile()), digest(path));
    }

    public void writeObservations(Path path, List<BenchmarkObservation> observations) throws IOException {
        createParent(path);
        try (BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            for (BenchmarkObservation observation : observations) {
                writer.write(lineWriter.writeValueAsString(observation));
                writer.newLine();
            }
        }
    }

    public void writeReport(Path path, BenchmarkReport report) throws IOException {
        createParent(path);
        reportWriter.writeValue(path.toFile(), report);
    }

    private static <T> List<T> readJsonLines(Path path, ObjectReader reader, Class<T> type) throws IOException {
        List<T> values = new ArrayList<>();
        try (BufferedReader input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = input.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    values.add(reader.readValue(line));
                } catch (IOException exception) {
                    throw new IOException(
                            "Invalid " + type.getSimpleName() + " at " + path + ":" + lineNumber,
                            exception);
                }
            }
        }
        if (values.isEmpty()) {
            throw new IOException("No records found in " + path);
        }
        return List.copyOf(values);
    }

    private static String digest(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
