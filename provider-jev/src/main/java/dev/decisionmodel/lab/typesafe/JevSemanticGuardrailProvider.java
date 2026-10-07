package dev.decisionmodel.lab.typesafe;

import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.SECONDS;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import dev.decisionmodel.lab.guardrail.ChoiceQuestion;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulQuestion;
import dev.decisionmodel.lab.guardrail.ScoreQuestion;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.SemanticGuardrailProvider;
import dev.decisionmodel.lab.guardrail.SemanticQuestion;
import dev.decisionmodel.lab.guardrail.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;

@ApplicationScoped
public class JevSemanticGuardrailProvider implements SemanticGuardrailProvider {

    private static final String PROVIDER_NAME = "jev";

    private final TypeSafeSystemOneClient client;
    private final Optional<String> apiKey;
    private final String model;

    @Inject
    public JevSemanticGuardrailProvider(
            @RestClient TypeSafeSystemOneClient client,
            @ConfigProperty(name = "typesafe.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "typesafe.model", defaultValue = "jev-latest") String model) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.model = requireText(model, "model");
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    @Retry(
            maxRetries = 2,
            delay = 200,
            delayUnit = MILLIS,
            retryOn = { JevRetryableException.class, ProcessingException.class })
    @Timeout(value = 5, unit = SECONDS)
    @CircuitBreaker(
            requestVolumeThreshold = 4,
            failureRatio = 0.5,
            delay = 10,
            delayUnit = SECONDS,
            successThreshold = 2,
            failOn = { JevRetryableException.class, ProcessingException.class })
    public GuardrailEvaluation evaluate(GuardrailRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String key = apiKey
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new JevConfigurationException("typesafe.api-key is required for the Jev path"));

        SystemOneDtos.Request payload = new SystemOneDtos.Request(
                request.state(),
                model,
                mapQuestions(request.questions()));

        long started = System.nanoTime();
        SystemOneDtos.Response response = client.evaluate("Bearer " + key, payload);
        Duration latency = Duration.ofNanos(System.nanoTime() - started);

        return mapResponse(request, response, latency);
    }

    private static Map<String, SystemOneDtos.Question> mapQuestions(
            Map<String, SemanticQuestion> questions) {
        Map<String, SystemOneDtos.Question> mapped = new LinkedHashMap<>();
        questions.forEach((id, question) -> mapped.put(id, mapQuestion(question)));
        return mapped;
    }

    private static SystemOneDtos.Question mapQuestion(SemanticQuestion question) {
        if (question instanceof NoulQuestion noul) {
            SystemOneDtos.NoulCriteria criteria = noul.trueCriteria() == null && noul.falseCriteria() == null
                    ? null
                    : new SystemOneDtos.NoulCriteria(noul.trueCriteria(), noul.falseCriteria());
            return new SystemOneDtos.NoulQuestion(noul.instructions(), criteria);
        }
        if (question instanceof ChoiceQuestion choice) {
            return new SystemOneDtos.ChoiceQuestion(choice.instructions(), choice.options());
        }
        if (question instanceof ScoreQuestion score) {
            return new SystemOneDtos.ScoreQuestion(score.instructions(), score.levels());
        }
        throw new JevProtocolException("Unsupported semantic question: " + question.getClass().getName());
    }

    private static GuardrailEvaluation mapResponse(
            GuardrailRequest request,
            SystemOneDtos.Response response,
            Duration latency) {
        if (response == null || response.answers() == null || response.usage() == null) {
            throw new JevProtocolException("TypeSafe returned an incomplete response");
        }
        if (!response.answers().keySet().equals(request.questions().keySet())) {
            throw new JevProtocolException("TypeSafe answer ids do not match the requested question ids");
        }

        Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
        request.questions().forEach((id, question) ->
                answers.put(id, mapAnswer(id, question, response.answers().get(id))));

        return new GuardrailEvaluation(
                PROVIDER_NAME,
                requireText(response.model(), "response model"),
                answers,
                new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens()),
                latency);
    }

    private static SemanticAnswer mapAnswer(
            String id,
            SemanticQuestion question,
            SystemOneDtos.Answer answer) {
        try {
            if (question instanceof NoulQuestion && answer instanceof SystemOneDtos.NoulAnswer noul) {
                return new dev.decisionmodel.lab.guardrail.NoulAnswer(noul.noul());
            }
            if (question instanceof ChoiceQuestion && answer instanceof SystemOneDtos.ChoiceAnswer choice) {
                return new dev.decisionmodel.lab.guardrail.ChoiceAnswer(
                        choice.choice(), choice.probabilities(), choice.confidence());
            }
            if (question instanceof ScoreQuestion && answer instanceof SystemOneDtos.ScoreAnswer score) {
                return new dev.decisionmodel.lab.guardrail.ScoreAnswer(
                        score.score(),
                        integerLegend(score.legend()),
                        integerProbabilities(score.probabilities()),
                        score.confidence());
            }
        } catch (IllegalArgumentException exception) {
            throw new JevProtocolException("Invalid answer for question '" + id + "'", exception);
        }
        throw new JevProtocolException("Answer type does not match question '" + id + "'");
    }

    private static Map<Integer, String> integerLegend(Map<String, String> legend) {
        if (legend == null) {
            throw new IllegalArgumentException("score legend must not be null");
        }
        Map<Integer, String> mapped = new LinkedHashMap<>();
        legend.forEach((level, description) -> mapped.put(parseLevel(level), description));
        return mapped;
    }

    private static Map<Integer, Double> integerProbabilities(Map<String, Double> probabilities) {
        if (probabilities == null) {
            throw new IllegalArgumentException("score probabilities must not be null");
        }
        Map<Integer, Double> mapped = new LinkedHashMap<>();
        probabilities.forEach((level, probability) -> mapped.put(parseLevel(level), probability));
        return mapped;
    }

    private static int parseLevel(String level) {
        try {
            return Integer.parseInt(level);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("score level must be an integer: " + level, exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new JevProtocolException(field + " must not be blank");
        }
        return value;
    }
}
