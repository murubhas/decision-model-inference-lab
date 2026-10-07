package dev.decisionmodel.lab.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.decisionmodel.lab.guardrail.GuardrailBoundary;
import dev.decisionmodel.lab.guardrail.runtime.HybridQuestionRoute;

class GuardrailPolicyCatalogTest {

    @Test
    void buildsInputContract() {
        var request = GuardrailPolicyCatalog.inputRequest("case-1", "Compare p5 and p5e.");

        assertEquals(GuardrailBoundary.INPUT, request.boundary());
        assertEquals(Set.of(
                GuardrailPolicyCatalog.PROMPT_INJECTION,
                GuardrailPolicyCatalog.IN_SCOPE), request.questions().keySet());
    }

    @Test
    void buildsToolAndOutputContracts() {
        var tool = GuardrailPolicyCatalog.toolCallRequest(
                "case-2", "Compare p5 and p5e.", "search", "{\"query\":\"p5 p5e\"}");
        var output = GuardrailPolicyCatalog.outputRequest(
                "case-3", "Compare p5 and p5e.", "p5e is the better fit based on the cited evidence.");

        assertEquals(GuardrailBoundary.TOOL_CALL, tool.boundary());
        assertEquals(2, tool.questions().size());
        assertEquals(GuardrailBoundary.OUTPUT, output.boundary());
        assertEquals(4, output.questions().size());
    }

    @Test
    void routesValidatedChecksToJevAndNuancedChecksToTheLlm() {
        var routing = GuardrailPolicyCatalog.hybridRoutingPolicy();
        var input = GuardrailPolicyCatalog.inputRequest("case-4", "Compare p5 and p5e.");
        var tool = GuardrailPolicyCatalog.toolCallRequest(
                "case-5", "Compare p5 and p5e.", "search", "{\"query\":\"p5 p5e\"}");
        var output = GuardrailPolicyCatalog.outputRequest(
                "case-6",
                "Compare p5 and p5e.",
                "p5e is the better fit based on the cited evidence.",
                List.of(Map.of("instanceType", "p5e.48xlarge")));

        assertEquals(List.of(GuardrailPolicyCatalog.PROMPT_INJECTION),
                routing.questionIds(input, HybridQuestionRoute.JEV_FIRST));
        assertEquals(List.of(GuardrailPolicyCatalog.IN_SCOPE),
                routing.questionIds(input, HybridQuestionRoute.LLM_DIRECT));
        assertEquals(List.of(
                GuardrailPolicyCatalog.TOOL_INTENT_ALIGNED,
                GuardrailPolicyCatalog.TOOL_ARGUMENTS_SAFE),
                routing.questionIds(tool, HybridQuestionRoute.JEV_FIRST));
        assertEquals(List.of(
                GuardrailPolicyCatalog.SENSITIVE_EXPOSURE,
                GuardrailPolicyCatalog.POLICY_LEAKAGE),
                routing.questionIds(output, HybridQuestionRoute.JEV_FIRST));
        assertEquals(List.of(
                GuardrailPolicyCatalog.RESPONSE_RELEVANT,
                GuardrailPolicyCatalog.RESPONSE_GROUNDED),
                routing.questionIds(output, HybridQuestionRoute.LLM_DIRECT));
    }

    @Test
    void projectsOnlyStateRequiredBySelectedChecks() {
        var routing = GuardrailPolicyCatalog.hybridRoutingPolicy();
        var output = GuardrailPolicyCatalog.outputRequest(
                "case-7",
                "Compare p5 and p5e.",
                "p5e is the better fit based on the cited evidence.",
                List.of(Map.of("instanceType", "p5e.48xlarge")));

        var jevRequest = routing.project(output, List.of(
                GuardrailPolicyCatalog.SENSITIVE_EXPOSURE,
                GuardrailPolicyCatalog.POLICY_LEAKAGE));
        var llmRequest = routing.project(output, List.of(
                GuardrailPolicyCatalog.RESPONSE_RELEVANT,
                GuardrailPolicyCatalog.RESPONSE_GROUNDED));

        assertEquals(Set.of("assistant_response"), jevRequest.state().keySet());
        assertEquals(Set.of("user_request", "assistant_response", "tool_evidence"),
                llmRequest.state().keySet());
    }
}
