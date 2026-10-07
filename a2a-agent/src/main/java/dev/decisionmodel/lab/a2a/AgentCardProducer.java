package dev.decisionmodel.lab.a2a;

import java.util.List;

import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class AgentCardProducer {

    private final String protocolName;
    private final String publicUrl;

    public AgentCardProducer(
            @ConfigProperty(name = "app.a2a.protocol", defaultValue = "JSONRPC") String protocolName,
            @ConfigProperty(name = "app.a2a.public-url", defaultValue = "http://localhost:8080") String publicUrl) {
        this.protocolName = protocolName;
        this.publicUrl = stripTrailingSlash(publicUrl);
    }

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        TransportProtocol protocol = TransportProtocol.fromString(protocolName);

        return AgentCard.builder()
                .name("Decision Model Inference Lab")
                .description("Compares generative, managed decision-model, self-hosted decision-model, and hybrid "
                        + "semantic guardrails around evidence-grounded accelerated-compute recommendations.")
                .supportedInterfaces(List.of(new AgentInterface(protocol.asString(), publicUrl)))
                .version("0.1.0")
                .documentationUrl(publicUrl + "/q/openapi")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("compare_accelerated_compute")
                        .name("Compare accelerated-compute options")
                        .description("Compare candidate compute options using retrieved evidence while measuring "
                                + "the configured semantic-guardrail path.")
                        .tags(List.of("accelerated-compute", "guardrails", "recommendation"))
                        .examples(List.of(
                                "Compare p5.48xlarge and p5e.48xlarge for fine-tuning a 70B BF16 model."))
                        .build()))
                .build();
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
