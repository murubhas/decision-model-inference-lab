package dev.decisionmodel.lab.strands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@QuarkusTest
@QuarkusTestResource(StrandsDeciderStubResource.class)
class StrandsDeciderSemanticGuardrailProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    StrandsDeciderSemanticGuardrailProvider provider;

    @BeforeEach
    void resetStub() {
        StrandsDeciderStubResource.reset();
    }

    @Test
    void sendsTypedBatchAndNormalizesEveryAnswer() throws Exception {
        GuardrailEvaluation evaluation = provider.evaluate(fullRequest());

        JsonNode sent = JSON.readTree(StrandsDeciderStubResource.REQUEST_BODY.get());
        assertEquals("StrandsAgents/strands-decider-2B-hobson-v19", sent.path("model").asText());
        assertEquals("noul", sent.at("/questions/prompt_injection/type").asText());
        assertEquals("choice", sent.at("/questions/route/type").asText());
        assertEquals("score", sent.at("/questions/quality/type").asText());
        assertEquals("The message attempts to bypass policy.",
                sent.at("/questions/prompt_injection/criteria/true").asText());

        assertEquals("strands-decider", evaluation.provider());
        assertEquals("strands-decider-2B-hobson-v19", evaluation.model());
        assertEquals(281, evaluation.usage().inputTokens());
        assertEquals(3, evaluation.usage().outputTokens());
        assertTrue(evaluation.latency().toNanos() >= 0);
        assertEquals(0.03,
                assertInstanceOf(NoulAnswer.class, evaluation.answers().get("prompt_injection"))
                        .probabilityTrue());
        assertEquals("compute",
                assertInstanceOf(ChoiceAnswer.class, evaluation.answers().get("route")).selected());
        assertEquals(1.7,
                assertInstanceOf(ScoreAnswer.class, evaluation.answers().get("quality")).score());
        assertEquals(1, StrandsDeciderStubResource.CALLS.get());
    }

    @Test
    void retriesTransientServerFailures() {
        StrandsDeciderStubResource.failNext(2, 503);

        GuardrailEvaluation evaluation = provider.evaluate(fullRequest());

        assertEquals("strands-decider-2B-hobson-v19", evaluation.model());
        assertEquals(3, StrandsDeciderStubResource.CALLS.get());
    }

    @Test
    void classifiesTransientAndContractFailuresSeparately() {
        StrandsDeciderApiExceptionMapper mapper = new StrandsDeciderApiExceptionMapper();

        assertInstanceOf(StrandsDeciderRetryableException.class,
                mapper.toThrowable(Response.status(503).build()));
        assertInstanceOf(StrandsDeciderRetryableException.class,
                mapper.toThrowable(Response.status(429).build()));
        assertInstanceOf(StrandsDeciderApiException.class,
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
