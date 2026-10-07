package dev.decisionmodel.lab.a2a;

import java.util.List;
import java.util.function.Supplier;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolProvider;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GuardedMcpToolProviderSupplier implements Supplier<ToolProvider> {

    private final McpClient mcpClient;
    private final ToolCallGuardrail guardrail;
    private final GuardrailInvocationContext context;

    @Inject
    public GuardedMcpToolProviderSupplier(
            @McpClientName("compute-tools") McpClient mcpClient,
            ToolCallGuardrail guardrail,
            GuardrailInvocationContext context) {
        this.mcpClient = mcpClient;
        this.guardrail = guardrail;
        this.context = context;
    }

    @Override
    public ToolProvider get() {
        List<String> allowedTools = SemanticToolCallGuardrail.allowedTools().stream().sorted().toList();
        return McpToolProvider.builder()
                .mcpClients(mcpClient)
                .filterToolNames(allowedTools)
                .failIfOneServerFails(true)
                .toolWrapper(delegate -> new GuardedMcpToolExecutor(
                        delegate,
                        guardrail,
                        context::recordToolEvidence))
                .build();
    }
}
