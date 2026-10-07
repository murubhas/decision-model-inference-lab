package dev.decisionmodel.lab.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.quarkiverse.langchain4j.guardrails.ToolGuardrailException;

class GuardedMcpToolExecutorTest {

    @Test
    void delegatesOnlyAfterGuardrailAllowsRequest() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<String> evidence = new AtomicReference<>();
        ToolExecutor delegate = (request, memoryId) -> {
            executions.incrementAndGet();
            return "tool-result";
        };
        GuardedMcpToolExecutor executor = new GuardedMcpToolExecutor(
                delegate,
                (request, context) -> {
                },
                (toolName, result) -> evidence.set(toolName + ":" + result));

        String result = executor.execute(tool(), "memory-1");

        assertEquals("tool-result", result);
        assertEquals(1, executions.get());
        assertEquals("search:tool-result", evidence.get());
    }

    @Test
    void rejectionNeverInvokesUnderlyingMcpExecutor() {
        AtomicInteger executions = new AtomicInteger();
        ToolExecutor delegate = (request, memoryId) -> {
            executions.incrementAndGet();
            return "must-not-run";
        };
        GuardedMcpToolExecutor executor = new GuardedMcpToolExecutor(delegate, (request, context) -> {
            throw new ToolGuardrailException("blocked", true);
        });

        assertThrows(ToolGuardrailException.class, () -> executor.execute(tool(), "memory-1"));
        assertEquals(0, executions.get());
    }

    private static ToolExecutionRequest tool() {
        return ToolExecutionRequest.builder()
                .id("call-search")
                .name("search")
                .arguments("{\"query\":\"p5\"}")
                .build();
    }
}
