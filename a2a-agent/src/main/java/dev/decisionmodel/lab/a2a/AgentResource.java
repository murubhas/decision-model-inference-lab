package dev.decisionmodel.lab.a2a;

import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.decisionmodel.lab.guardrail.runtime.SemanticPath;
import dev.decisionmodel.lab.llm.BedrockSemanticGuardrailProvider;
import dev.decisionmodel.lab.strands.StrandsDeciderSemanticGuardrailProvider;
import dev.decisionmodel.lab.typesafe.JevSemanticGuardrailProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/agent/status")
public class AgentResource {

    private final BedrockSemanticGuardrailProvider llmProvider;
    private final JevSemanticGuardrailProvider jevProvider;
    private final StrandsDeciderSemanticGuardrailProvider deciderProvider;
    private final SemanticPath semanticPath;

    @Inject
    public AgentResource(
            BedrockSemanticGuardrailProvider llmProvider,
            JevSemanticGuardrailProvider jevProvider,
            StrandsDeciderSemanticGuardrailProvider deciderProvider,
            @ConfigProperty(name = "guardrail.semantic.mode", defaultValue = "HYBRID") SemanticPath semanticPath) {
        this.llmProvider = llmProvider;
        this.jevProvider = jevProvider;
        this.deciderProvider = deciderProvider;
        this.semanticPath = semanticPath;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AgentStatus status() {
        return new AgentStatus(
                "decision-model-inference-lab",
                "a2a-jsonrpc",
                "jvm",
                semanticPath,
                List.of(llmProvider.name(), jevProvider.name(), deciderProvider.name()));
    }

    public record AgentStatus(
            String service,
            String stage,
            String runtime,
            SemanticPath semanticPath,
            List<String> semanticProviders) {
    }
}
