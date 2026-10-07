package dev.decisionmodel.lab.a2a;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class AgentResourceTest {
    @Test
    void reportsBootstrapStatus() {
        given()
          .when().get("/agent/status")
          .then()
             .statusCode(200)
             .body("service", is("decision-model-inference-lab"))
             .body("stage", is("a2a-jsonrpc"))
             .body("runtime", is("jvm"))
             .body("semanticPath", is("HYBRID"))
             .body("semanticProviders[0]", is("llm-bedrock"))
             .body("semanticProviders[1]", is("jev"))
             .body("semanticProviders[2]", is("strands-decider"));
    }

    @Test
    void exposesPrometheusMetrics() {
        given()
          .when().get("/agent/status")
          .then().statusCode(200);

        given()
          .when().get("/q/metrics")
          .then()
             .statusCode(200)
             .body(containsString("http_server_requests_seconds"));
    }

    @Test
    void publishesA2AAgentCard() {
        given()
          .when().get("/.well-known/agent-card.json")
          .then()
             .statusCode(200)
             .body("name", is("Decision Model Inference Lab"))
             .body("version", is("0.1.0"))
             .body("supportedInterfaces[0].protocolBinding", is("JSONRPC"))
             .body("supportedInterfaces[0].url", is("http://localhost:8080"))
             .body("skills[0].id", is("compare_accelerated_compute"));
    }

    @Test
    void acceptsA2AMessageOverJsonRpc() {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": "request-1",
                  "method": "SendMessage",
                  "params": {
                    "message": {
                      "messageId": "message-1",
                      "role": "ROLE_USER",
                      "parts": [
                        {"text": "Compare p5.48xlarge and p5e.48xlarge."}
                      ]
                    }
                  }
                }
                """;

        given()
          .contentType("application/json")
          .header("A2A-Version", "1.0")
          .body(request)
          .when().post("/")
          .then()
             .statusCode(200)
             .body("jsonrpc", is("2.0"))
             .body("id", is("request-1"))
             .body("result.message.parts[0].text",
                     containsString("A2A request accepted"));
    }
}
