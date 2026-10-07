package dev.decisionmodel.lab.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.decisionmodel.lab.guardrail.ChoiceAnswer;
import dev.decisionmodel.lab.guardrail.ChoiceQuestion;
import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.GuardrailEvaluation;
import dev.decisionmodel.lab.guardrail.GuardrailRequest;
import dev.decisionmodel.lab.guardrail.NoulAnswer;
import dev.decisionmodel.lab.guardrail.NoulQuestion;
import dev.decisionmodel.lab.guardrail.ScoreAnswer;
import dev.decisionmodel.lab.guardrail.ScoreQuestion;
import dev.decisionmodel.lab.guardrail.SemanticQuestion;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.quarkiverse.langchain4j.RegisterAiService;

class BedrockSemanticGuardrailProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void disablesChatMemoryForIndependentGuardrailDecisions() {
        RegisterAiService registration = BedrockGuardrailJudge.class.getAnnotation(RegisterAiService.class);

        assertEquals(
                RegisterAiService.NoChatMemoryProviderSupplier.class,
                registration.chatMemoryProviderSupplier());
    }

    @Test
    void mapsEveryQuestionTypeAndRetainsBedrockMetadata() throws Exception {
        CapturingJudge judge = new CapturingJudge(successfulResult("""
                {
                  "answers": {
                    "in_scope": {"type":"noul","probability_true":0.94},
                    "intent": {
                      "type":"choice",
                      "selected":"compare",
                      "probabilities":{"compare":0.8,"explain":0.2},
                      "confidence":0.76
                    },
                    "risk": {
                      "type":"score",
                      "score":1.3,
                      "probabilities":{"0":0.1,"1":0.5,"2":0.4},
                      "confidence":0.65
                    }
                  }
                }
                """, "anthropic.claude-sonnet-5-5-snapshot", 321, 87));
        BedrockSemanticGuardrailProvider provider = provider(judge);

        GuardrailEvaluation evaluation = provider.evaluate(request());

        assertEquals("llm-bedrock", evaluation.provider());
        assertEquals("anthropic.claude-sonnet-5-5-snapshot", evaluation.model());
        assertEquals(321, evaluation.usage().inputTokens());
        assertEquals(87, evaluation.usage().outputTokens());
        assertEquals(0.94, assertInstanceOf(NoulAnswer.class, evaluation.answers().get("in_scope"))
                .probabilityTrue());
        assertEquals("compare", assertInstanceOf(ChoiceAnswer.class, evaluation.answers().get("intent"))
                .selected());
        assertEquals(1.3, assertInstanceOf(ScoreAnswer.class, evaluation.answers().get("risk")).score());

        JsonNode prompt = MAPPER.readTree(judge.prompt);
        assertEquals("input", prompt.path("boundary").asText());
        assertEquals("Compare p5 and p5e", prompt.path("state").path("message").asText());
        assertEquals("noul", prompt.path("questions").path("in_scope").path("type").asText());
        assertEquals("Critical", prompt.path("questions").path("risk").path("levels").path("2").asText());
    }

    @Test
    void usesConfiguredModelWhenProviderMetadataDoesNotNameOne() {
        BedrockGuardrailJudge judge = request -> Result.<String>builder()
                .content("""
                        {"answers":{"in_scope":{"type":"noul","probability_true":0.7}}}
                        """)
                .tokenUsage(new TokenUsage(10, 4))
                .build();
        BedrockSemanticGuardrailProvider provider = new BedrockSemanticGuardrailProvider(
                judge, MAPPER, "global.anthropic.claude-opus-5-5");

        GuardrailEvaluation evaluation = provider.evaluate(noulOnlyRequest());

        assertEquals("global.anthropic.claude-opus-5-5", evaluation.model());
    }

    @Test
    void rejectsMarkdownWrappedJsonInsteadOfSilentlyRepairingIt() {
        BedrockGuardrailJudge judge = request -> successfulResult(
                "```json\n{\"answers\":{}}\n```", "model", 1, 1);

        LlmProtocolException exception = assertThrows(
                LlmProtocolException.class,
                () -> provider(judge).evaluate(noulOnlyRequest()));

        assertTrue(exception.getMessage().contains("not valid contract JSON"));
    }

    @Test
    void rejectsMissingAnswerIds() {
        BedrockGuardrailJudge judge = request -> successfulResult(
                "{\"answers\":{}}", "model", 1, 1);

        LlmProtocolException exception = assertThrows(
                LlmProtocolException.class,
                () -> provider(judge).evaluate(noulOnlyRequest()));

        assertTrue(exception.getMessage().contains("answer ids"));
    }

    @Test
    void rejectsChoiceDistributionsThatOmitRequestedOptions() {
        GuardrailRequest request = new GuardrailRequest(
                "corr-choice",
                GuardrailBoundary.INPUT,
                Map.of("message", "Compare two options"),
                Map.of("intent", new ChoiceQuestion(
                        "What is the intent?",
                        Map.of("compare", "Compare options", "explain", "Explain one option"))));
        BedrockGuardrailJudge judge = ignored -> successfulResult("""
                {
                  "answers": {
                    "intent": {
                      "type":"choice",
                      "selected":"compare",
                      "probabilities":{"compare":1.0},
                      "confidence":1.0
                    }
                  }
                }
                """, "model", 1, 1);

        LlmProtocolException exception = assertThrows(
                LlmProtocolException.class,
                () -> provider(judge).evaluate(request));

        assertTrue(exception.getMessage().contains("Invalid answer"));
    }

    @Test
    void rejectsResponsesWithoutTokenAccounting() {
        BedrockGuardrailJudge judge = request -> Result.<String>builder()
                .content("""
                        {"answers":{"in_scope":{"type":"noul","probability_true":0.7}}}
                        """)
                .build();

        LlmProtocolException exception = assertThrows(
                LlmProtocolException.class,
                () -> provider(judge).evaluate(noulOnlyRequest()));

        assertTrue(exception.getMessage().contains("token usage"));
    }

    private static BedrockSemanticGuardrailProvider provider(BedrockGuardrailJudge judge) {
        return new BedrockSemanticGuardrailProvider(
                judge, MAPPER, "global.anthropic.claude-sonnet-5-5");
    }

    private static Result<String> successfulResult(
            String content,
            String model,
            int inputTokens,
            int outputTokens) {
        TokenUsage usage = new TokenUsage(inputTokens, outputTokens);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(content))
                .modelName(model)
                .tokenUsage(usage)
                .finishReason(FinishReason.STOP)
                .build();
        return Result.<String>builder()
                .content(content)
                .tokenUsage(usage)
                .finalResponse(response)
                .build();
    }

    private static GuardrailRequest noulOnlyRequest() {
        return new GuardrailRequest(
                "corr-noul",
                GuardrailBoundary.INPUT,
                Map.of("message", "Compare p5 and p5e"),
                Map.of("in_scope", new NoulQuestion("Is the request in scope?")));
    }

    private static GuardrailRequest request() {
        Map<String, SemanticQuestion> questions = new LinkedHashMap<>();
        questions.put("in_scope", new NoulQuestion(
                "Is the request in scope?",
                "It concerns accelerated compute.",
                "It is unrelated to accelerated compute."));
        questions.put("intent", new ChoiceQuestion(
                "What is the requested task?",
                Map.of("compare", "Compare candidates", "explain", "Explain one candidate")));
        questions.put("risk", new ScoreQuestion(
                "How consequential is a wrong answer?",
                List.of("Low", "Material", "Critical")));
        return new GuardrailRequest(
                "corr-all",
                GuardrailBoundary.INPUT,
                Map.of("message", "Compare p5 and p5e"),
                questions);
    }

    private static final class CapturingJudge implements BedrockGuardrailJudge {

        private final Result<String> result;
        private String prompt;

        private CapturingJudge(Result<String> result) {
            this.result = result;
        }

        @Override
        public Result<String> evaluate(String requestJson) {
            prompt = requestJson;
            return result;
        }
    }
}
