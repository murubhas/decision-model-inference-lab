package dev.decisionmodel.lab.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.quarkiverse.langchain4j.RegisterAiService;

class RecommendationAgentServiceTest {

    @Test
    void usesGuardedMcpToolProvider() {
        RegisterAiService registration = RecommendationAssistant.class.getAnnotation(RegisterAiService.class);

        assertEquals(GuardedMcpToolProviderSupplier.class, registration.toolProviderSupplier());
    }

    @Test
    void retainsRecommendationModelUsageAndText() {
        RecommendationAssistant assistant = request -> result(
                "p5e.48xlarge is the grounded choice.",
                "anthropic.claude-sonnet-5-5-snapshot",
                550,
                80);
        RecommendationAgentService service = new RecommendationAgentService(
                assistant,
                "global.anthropic.claude-sonnet-5-5");

        RecommendationResponse response = service.recommend("Compare p5 and p5e");

        assertEquals("p5e.48xlarge is the grounded choice.", response.text());
        assertEquals("anthropic.claude-sonnet-5-5-snapshot", response.model());
        assertEquals(550, response.usage().inputTokens());
        assertEquals(80, response.usage().outputTokens());
    }

    @Test
    void fallsBackToConfiguredModelWhenResponseMetadataOmitsIt() {
        RecommendationAssistant assistant = request -> Result.<String>builder()
                .content("Grounded answer")
                .tokenUsage(new TokenUsage(10, 3))
                .build();
        RecommendationAgentService service = new RecommendationAgentService(
                assistant,
                "global.anthropic.claude-opus-5-5");

        assertEquals("global.anthropic.claude-opus-5-5", service.recommend("question").model());
    }

    @Test
    void rejectsMissingUsageForBenchmarkIntegrity() {
        RecommendationAssistant assistant = request -> Result.<String>builder()
                .content("Grounded answer")
                .build();
        RecommendationAgentService service = new RecommendationAgentService(
                assistant,
                "global.anthropic.claude-sonnet-5-5");

        assertThrows(IllegalStateException.class, () -> service.recommend("question"));
    }

    private static Result<String> result(String text, String model, int inputTokens, int outputTokens) {
        TokenUsage usage = new TokenUsage(inputTokens, outputTokens);
        return Result.<String>builder()
                .content(text)
                .tokenUsage(usage)
                .finalResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(text))
                        .modelName(model)
                        .tokenUsage(usage)
                        .build())
                .build();
    }
}
