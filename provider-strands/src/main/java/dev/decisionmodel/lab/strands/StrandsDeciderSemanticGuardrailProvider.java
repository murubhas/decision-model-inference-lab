package dev.decisionmodel.lab.strands;

import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.SECONDS;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
public class StrandsDeciderSemanticGuardrailProvider implements SemanticGuardrailProvider {

    private static final String PROVIDER_NAME = "strands-decider";

    private final StrandsDeciderClient client;
    private final String model;

    @Inject
    public StrandsDeciderSemanticGuardrailProvider(
            @RestClient StrandsDeciderClient client,
            @ConfigProperty(
                    name = "strands-decider.model",
                    defaultValue = "strands-decider-latest") String model) {
        this.client = Objects.requireNonNull(client, "client must not be null");
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
            retryOn = { StrandsDeciderRetryableException.class, ProcessingException.class })
    @Timeout(value = 15, unit = SECONDS)
    @CircuitBreaker(
            requestVolumeThreshold = 4,
            failureRatio = 0.5,
            delay = 10,
            delayUnit = SECONDS,
            successThreshold = 2,
            failOn = { StrandsDeciderRetryableException.class, ProcessingException.class })
    public GuardrailEvaluation evaluate(GuardrailRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        StrandsSystemOneDtos.Request payload = new StrandsSystemOneDtos.Request(
                request.state(),
                model,
                mapQuestions(request.questions()));

        long started = System.nanoTime();
        StrandsSystemOneDtos.Response response = client.evaluate(payload);
        Duration latency = Duration.ofNanos(System.nanoTime() - started);

        return mapResponse(request, response, latency);
    }

    private static Map<String, StrandsSystemOneDtos.Question> mapQuestions(
            Map<String, SemanticQuestion> questions) {
        Map<String, StrandsSystemOneDtos.Question> mapped = new LinkedHashMap<>();
        questions.forEach((id, question) -> mapped.put(id, mapQuestion(question)));
        return mapped;
    }

    private static StrandsSystemOneDtos.Question mapQuestion(SemanticQuestion question) {
        if (question instanceof NoulQuestion noul) {
            StrandsSystemOneDtos.NoulCriteria criteria =
                    noul.trueCriteria() == null && noul.falseCriteria() == null
                            ? null
                            : new StrandsSystemOneDtos.NoulCriteria(
                                    noul.trueCriteria(), noul.falseCriteria());
            return new StrandsSystemOneDtos.NoulQuestion(noul.instructions(), criteria);
        }
        if (question instanceof ChoiceQuestion choice) {
            return new StrandsSystemOneDtos.ChoiceQuestion(
                    choice.instructions(), choice.options());
        }
        if (question instanceof ScoreQuestion score) {
            return new StrandsSystemOneDtos.ScoreQuestion(
                    score.instructions(), score.levels());
        }
        throw new StrandsDeciderProtocolException(
                "Unsupported semantic question: " + question.getClass().getName());
    }

    private static GuardrailEvaluation mapResponse(
            GuardrailRequest request,
            StrandsSystemOneDtos.Response response,
            Duration latency) {
        if (response == null || response.answers() == null || response.usage() == null) {
            throw new StrandsDeciderProtocolException("Strands Decider returned an incomplete response");
        }
        if (!response.answers().keySet().equals(request.questions().keySet())) {
            throw new StrandsDeciderProtocolException(
                    "Strands Decider answer ids do not match the requested question ids");
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
            StrandsSystemOneDtos.Answer answer) {
        try {
            if (question instanceof NoulQuestion
                    && answer instanceof StrandsSystemOneDtos.NoulAnswer noul) {
                return new dev.decisionmodel.lab.guardrail.NoulAnswer(noul.noul());
            }
            if (question instanceof ChoiceQuestion
                    && answer instanceof StrandsSystemOneDtos.ChoiceAnswer choice) {
                return new dev.decisionmodel.lab.guardrail.ChoiceAnswer(
                        choice.choice(), choice.probabilities(), choice.confidence());
            }
            if (question instanceof ScoreQuestion
                    && answer instanceof StrandsSystemOneDtos.ScoreAnswer score) {
                return new dev.decisionmodel.lab.guardrail.ScoreAnswer(
                        score.score(),
                        integerLegend(score.legend()),
                        integerProbabilities(score.probabilities()),
                        score.confidence());
            }
        } catch (IllegalArgumentException exception) {
            throw new StrandsDeciderProtocolException(
                    "Invalid answer for question '" + id + "'", exception);
        }
        throw new StrandsDeciderProtocolException(
                "Answer type does not match question '" + id + "'");
    }

    private static Map<Integer, String> integerLegend(Map<String, String> legend) {
        if (legend == null) {
            throw new IllegalArgumentException("score legend must not be null");
        }
        Map<Integer, String> mapped = new LinkedHashMap<>();
        legend.forEach((level, description) -> mapped.put(parseLevel(level), description));
        return mapped;
    }

    private static Map<Integer, Double> integerProbabilities(
            Map<String, Double> probabilities) {
        if (probabilities == null) {
            throw new IllegalArgumentException("score probabilities must not be null");
        }
        Map<Integer, Double> mapped = new LinkedHashMap<>();
        probabilities.forEach((level, probability) ->
                mapped.put(parseLevel(level), probability));
        return mapped;
    }

    private static int parseLevel(String level) {
        try {
            return Integer.parseInt(level);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "score level must be an integer: " + level, exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new StrandsDeciderProtocolException(field + " must not be blank");
        }
        return value;
    }
}
