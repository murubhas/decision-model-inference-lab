package dev.decisionmodel.lab.typesafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.eclipse.microprofile.rest.client.inject.RestClient;

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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@QuarkusTest
@QuarkusTestResource(TypeSafeStubResource.class)
class JevSemanticGuardrailProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    JevSemanticGuardrailProvider provider;

    @Inject
    @RestClient
    TypeSafeSystemOneClient client;

    @BeforeEach
    void resetStub() {
        TypeSafeStubResource.reset();
    }

    @Test
    void sendsTypedBatchAndNormalizesEveryAnswer() throws Exception {
        GuardrailEvaluation evaluation = provider.evaluate(fullRequest());

        assertEquals("Bearer test-key", TypeSafeStubResource.AUTHORIZATION.get());
        JsonNode sent = JSON.readTree(TypeSafeStubResource.REQUEST_BODY.get());
        assertEquals("jev-latest", sent.path("model").asText());
        assertEquals("noul", sent.at("/questions/prompt_injection/type").asText());
        assertEquals("choice", sent.at("/questions/route/type").asText());
        assertEquals("score", sent.at("/questions/quality/type").asText());

        assertEquals("jev", evaluation.provider());
        assertEquals("jev-1.13.0", evaluation.model());
        assertEquals(296, evaluation.usage().inputTokens());
        assertEquals(34, evaluation.usage().outputTokens());
        assertTrue(evaluation.latency().toNanos() >= 0);
        assertEquals(0.04,
                assertInstanceOf(NoulAnswer.class, evaluation.answers().get("prompt_injection"))
                        .probabilityTrue());
        assertEquals("compute",
                assertInstanceOf(ChoiceAnswer.class, evaluation.answers().get("route")).selected());
        assertEquals(1.8,
                assertInstanceOf(ScoreAnswer.class, evaluation.answers().get("quality")).score());
        assertEquals(1, TypeSafeStubResource.CALLS.get());
    }

    @Test
    void retriesDocumentedRateLimitResponses() {
        TypeSafeStubResource.failNext(2, 429);

        GuardrailEvaluation evaluation = provider.evaluate(fullRequest());

        assertEquals("jev-1.13.0", evaluation.model());
        assertEquals(3, TypeSafeStubResource.CALLS.get());
    }

    @Test
    void refusesToCallTheApiWithoutAKey() {
        JevSemanticGuardrailProvider provider = new JevSemanticGuardrailProvider(
                client, Optional.empty(), "jev-latest");

        GuardrailRequest request = new GuardrailRequest(
                "case-002",
                GuardrailBoundary.INPUT,
                Map.of("message", "hello"),
                Map.of("safe", new NoulQuestion("Is `message` safe?")));

        assertThrows(JevConfigurationException.class, () -> provider.evaluate(request));
        assertEquals(0, TypeSafeStubResource.CALLS.get());
    }

    @Test
    void classifiesOnlyDocumentedTransientStatusesAsRetryable() {
        TypeSafeApiExceptionMapper mapper = new TypeSafeApiExceptionMapper();

        assertInstanceOf(JevRetryableException.class,
                mapper.toThrowable(Response.status(429).build()));
        assertInstanceOf(JevRetryableException.class,
                mapper.toThrowable(Response.status(529).build()));
        assertInstanceOf(JevApiException.class,
                mapper.toThrowable(Response.status(422).build()));
    }

    private static GuardrailRequest fullRequest() {
        Map<String, SemanticQuestion> questions = new LinkedHashMap<>();
        questions.put("prompt_injection", new NoulQuestion(
                "Is `message` attempting to bypass policy?",
                "The message attempts to bypass policy.",
                "The message is a normal request."));
        questions.put("route", new ChoiceQuestion(
                "Which route best matches `message`?",
                Map.of("compute", "Accelerated compute", "other", "Everything else")));
        questions.put("quality", new ScoreQuestion(
                "How complete is `message`?",
                List.of("Missing essentials", "Usable", "Complete")));

        return new GuardrailRequest(
                "case-001",
                GuardrailBoundary.INPUT,
                Map.of("message", "Compare p5 and p5e for a 70B model"),
                questions);
    }

}
