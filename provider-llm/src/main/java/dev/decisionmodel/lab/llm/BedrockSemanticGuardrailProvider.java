package dev.decisionmodel.lab.llm;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import dev.decisionmodel.lab.guardrail.ChoiceQuestion;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulQuestion;
import dev.decisionmodel.lab.guardrail.ScoreQuestion;
import dev.decisionmodel.lab.guardrail.SemanticAnswer;
import dev.decisionmodel.lab.guardrail.SemanticGuardrailProvider;
import dev.decisionmodel.lab.guardrail.SemanticQuestion;
import dev.decisionmodel.lab.guardrail.TokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BedrockSemanticGuardrailProvider implements SemanticGuardrailProvider {

    private static final String PROVIDER_NAME = "llm-bedrock";

    private final BedrockGuardrailJudge judge;
    private final ObjectWriter promptWriter;
    private final ObjectReader responseReader;
    private final String configuredModel;

    @Inject
    public BedrockSemanticGuardrailProvider(
            BedrockGuardrailJudge judge,
            ObjectMapper objectMapper,
            @ConfigProperty(
                    name = "guardrail.llm.model-id",
                    defaultValue = "global.anthropic.claude-sonnet-5-5") String configuredModel) {
        this.judge = Objects.requireNonNull(judge, "judge must not be null");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.promptWriter = mapper.writer();
        this.responseReader = mapper.readerFor(LlmDecisionDtos.Response.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.configuredModel = requireText(configuredModel, "configuredModel");
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public GuardrailEvaluation evaluate(GuardrailRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String prompt = serializePrompt(request);

        long started = System.nanoTime();
        Result<String> result = judge.evaluate(prompt);
        Duration latency = Duration.ofNanos(System.nanoTime() - started);

        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new LlmProtocolException("Bedrock returned an empty guardrail response");
        }

        LlmDecisionDtos.Response response = parseResponse(result.content());
        Map<String, SemanticAnswer> answers = mapAnswers(request, response);
        return new GuardrailEvaluation(
                PROVIDER_NAME,
                responseModel(result),
                answers,
                tokenUsage(result),
                latency);
    }

    private String serializePrompt(GuardrailRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("correlation_id", request.correlationId());
        payload.put("boundary", request.boundary().name().toLowerCase());
        payload.put("state", request.state());

        Map<String, Object> questions = new LinkedHashMap<>();
        request.questions().forEach((id, question) -> questions.put(id, promptQuestion(question)));
        payload.put("questions", questions);

        try {
            return promptWriter.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmProtocolException("Unable to serialize the LLM guardrail request", exception);
        }
    }

    private static Map<String, Object> promptQuestion(SemanticQuestion question) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("type", question.type().name().toLowerCase());
        mapped.put("instructions", question.instructions());
        if (question instanceof NoulQuestion noul) {
            Map<String, String> criteria = new LinkedHashMap<>();
            if (noul.trueCriteria() != null) {
                criteria.put("true", noul.trueCriteria());
            }
            if (noul.falseCriteria() != null) {
                criteria.put("false", noul.falseCriteria());
            }
            if (!criteria.isEmpty()) {
                mapped.put("criteria", criteria);
            }
        } else if (question instanceof ChoiceQuestion choice) {
            mapped.put("options", choice.options());
        } else if (question instanceof ScoreQuestion score) {
            mapped.put("levels", indexedLevels(score));
        } else {
            throw new LlmProtocolException("Unsupported semantic question: " + question.getClass().getName());
        }
        return mapped;
    }

    private LlmDecisionDtos.Response parseResponse(String content) {
        try {
            return responseReader.readValue(content);
        } catch (JsonProcessingException exception) {
            throw new LlmProtocolException("Bedrock guardrail response is not valid contract JSON", exception);
        }
    }

    private static Map<String, SemanticAnswer> mapAnswers(
            GuardrailRequest request,
            LlmDecisionDtos.Response response) {
        if (response == null || response.answers() == null) {
            throw new LlmProtocolException("Bedrock returned an incomplete guardrail response");
        }
        if (!response.answers().keySet().equals(request.questions().keySet())) {
            throw new LlmProtocolException("Bedrock answer ids do not match the requested question ids");
        }

        Map<String, SemanticAnswer> answers = new LinkedHashMap<>();
        request.questions().forEach((id, question) ->
                answers.put(id, mapAnswer(id, question, response.answers().get(id))));
        return answers;
    }

    private static SemanticAnswer mapAnswer(
            String id,
            SemanticQuestion question,
            LlmDecisionDtos.Answer answer) {
        try {
            if (question instanceof NoulQuestion && answer instanceof LlmDecisionDtos.NoulAnswer noul) {
                return new dev.decisionmodel.lab.guardrail.NoulAnswer(noul.probabilityTrue());
            }
            if (question instanceof ChoiceQuestion choiceQuestion
                    && answer instanceof LlmDecisionDtos.ChoiceAnswer choice) {
                requireSameKeys(choiceQuestion.options(), choice.probabilities(), "choice probabilities");
                return new dev.decisionmodel.lab.guardrail.ChoiceAnswer(
                        choice.selected(), choice.probabilities(), choice.confidence());
            }
            if (question instanceof ScoreQuestion scoreQuestion
                    && answer instanceof LlmDecisionDtos.ScoreAnswer score) {
                Map<Integer, String> legend = indexedLevels(scoreQuestion);
                requireSameKeys(legend, score.probabilities(), "score probabilities");
                return new dev.decisionmodel.lab.guardrail.ScoreAnswer(
                        score.score(), legend, score.probabilities(), score.confidence());
            }
        } catch (IllegalArgumentException exception) {
            throw new LlmProtocolException("Invalid answer for question '" + id + "'", exception);
        }
        throw new LlmProtocolException("Answer type does not match question '" + id + "'");
    }

    private static Map<Integer, String> indexedLevels(ScoreQuestion question) {
        Map<Integer, String> levels = new LinkedHashMap<>();
        for (int index = 0; index < question.levels().size(); index++) {
            levels.put(index, question.levels().get(index));
        }
        return levels;
    }

    private static void requireSameKeys(Map<?, ?> expected, Map<?, ?> actual, String field) {
        if (actual == null || !actual.keySet().equals(expected.keySet())) {
            throw new IllegalArgumentException(field + " must contain exactly the requested keys");
        }
    }

    private String responseModel(Result<String> result) {
        ChatResponse response = result.finalResponse();
        if (response != null && response.modelName() != null && !response.modelName().isBlank()) {
            return response.modelName();
        }
        return configuredModel;
    }

    private static TokenUsage tokenUsage(Result<String> result) {
        dev.langchain4j.model.output.TokenUsage usage = result.tokenUsage();
        if (usage == null || usage.inputTokenCount() == null || usage.outputTokenCount() == null) {
            throw new LlmProtocolException("Bedrock response did not include input and output token usage");
        }
        return new TokenUsage(usage.inputTokenCount(), usage.outputTokenCount());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
