package dev.decisionmodel.lab.a2a;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;

@FunctionalInterface
public interface ToolCallGuardrail {

    void validate(ToolExecutionRequest request, InvocationContext invocationContext);
}
