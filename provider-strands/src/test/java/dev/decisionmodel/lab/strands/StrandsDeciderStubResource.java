package dev.decisionmodel.lab.strands;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public final class StrandsDeciderStubResource implements QuarkusTestResourceLifecycleManager {

    static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicInteger FAILURES_REMAINING = new AtomicInteger();
    static final AtomicInteger FAILURE_STATUS = new AtomicInteger();

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/systemone", this::handle);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the local Strands Decider stub", exception);
        }

        return Map.of(
                "quarkus.rest-client.strands-decider.url",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "strands-decider.model",
                "StrandsAgents/strands-decider-2B-hobson-v19");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static void reset() {
        REQUEST_BODY.set(null);
        CALLS.set(0);
        FAILURES_REMAINING.set(0);
        FAILURE_STATUS.set(0);
    }

    static void failNext(int count, int status) {
        FAILURES_REMAINING.set(count);
        FAILURE_STATUS.set(status);
    }

    private void handle(HttpExchange exchange) throws IOException {
        CALLS.incrementAndGet();
        REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        int remaining = FAILURES_REMAINING.getAndUpdate(value -> Math.max(0, value - 1));
        if (remaining > 0) {
            exchange.sendResponseHeaders(FAILURE_STATUS.get(), -1);
            exchange.close();
            return;
        }

        byte[] body = """
                {
                  "model": "strands-decider-2B-hobson-v19",
                  "answers": {
                    "prompt_injection": {"type": "noul", "noul": 0.03},
                    "route": {
                      "type": "choice",
                      "choice": "compute",
                      "probabilities": {"compute": 0.92, "other": 0.08},
                      "confidence": 0.84
                    },
                    "quality": {
                      "type": "score",
                      "score": 1.7,
                      "legend": {"0": "Missing essentials", "1": "Usable", "2": "Complete"},
                      "probabilities": {"0": 0.0, "1": 0.3, "2": 0.7},
                      "confidence": 0.70
                    }
                  },
                  "usage": {"input_tokens": 281, "output_tokens": 3},
                  "latency_ms": 118.4
                }
                """.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
