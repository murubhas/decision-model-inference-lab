package dev.decisionmodel.lab.a2a;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;

final class GuardedMcpToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final ToolCallGuardrail guardrail;
    private final BiConsumer<String, String> evidenceRecorder;

    GuardedMcpToolExecutor(ToolExecutor delegate, ToolCallGuardrail guardrail) {
        this(delegate, guardrail, (toolName, result) -> {
        });
    }

    GuardedMcpToolExecutor(
            ToolExecutor delegate,
            ToolCallGuardrail guardrail,
            BiConsumer<String, String> evidenceRecorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.guardrail = Objects.requireNonNull(guardrail, "guardrail must not be null");
        this.evidenceRecorder = Objects.requireNonNull(evidenceRecorder, "evidenceRecorder must not be null");
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        guardrail.validate(request, null);
        String result = delegate.execute(request, memoryId);
        evidenceRecorder.accept(request.name(), result);
        return result;
    }

    @Override
    public ToolExecutionResult executeWithContext(
            ToolExecutionRequest request,
            InvocationContext invocationContext) {
        guardrail.validate(request, invocationContext);
        ToolExecutionResult result = delegate.executeWithContext(request, invocationContext);
        record(request, result);
        return result;
    }

    @Override
    public CompletableFuture<ToolExecutionResult> executeAsync(
            ToolExecutionRequest request,
            InvocationContext invocationContext) {
        try {
            guardrail.validate(request, invocationContext);
            return delegate.executeAsync(request, invocationContext)
                    .thenApply(result -> {
                        record(request, result);
                        return result;
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void record(ToolExecutionRequest request, ToolExecutionResult result) {
        String text = result == null ? "" : result.resultText();
        evidenceRecorder.accept(request.name(), text == null ? "" : text);
    }
}
