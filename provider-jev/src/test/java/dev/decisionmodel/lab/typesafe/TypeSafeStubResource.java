package dev.decisionmodel.lab.typesafe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public final class TypeSafeStubResource implements QuarkusTestResourceLifecycleManager {

    static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicInteger FAILURES_REMAINING = new AtomicInteger();
    static final AtomicInteger FAILURE_STATUS = new AtomicInteger();

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/systemone", this::handleSuccess);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the local TypeSafe stub", exception);
        }

        return Map.of(
                "quarkus.rest-client.typesafe.url", "http://127.0.0.1:" + server.getAddress().getPort(),
                "typesafe.api-key", "test-key",
                "typesafe.model", "jev-latest");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static void reset() {
        AUTHORIZATION.set(null);
        REQUEST_BODY.set(null);
        CALLS.set(0);
        FAILURES_REMAINING.set(0);
        FAILURE_STATUS.set(0);
    }

    static void failNext(int count, int status) {
        FAILURES_REMAINING.set(count);
        FAILURE_STATUS.set(status);
    }

    private void handleSuccess(HttpExchange exchange) throws IOException {
        CALLS.incrementAndGet();
        AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        int remaining = FAILURES_REMAINING.getAndUpdate(value -> Math.max(0, value - 1));
        if (remaining > 0) {
            exchange.sendResponseHeaders(FAILURE_STATUS.get(), -1);
            exchange.close();
            return;
        }

        byte[] body = """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "prompt_injection": {"type": "noul", "noul": 0.04},
                    "route": {
                      "type": "choice",
                      "choice": "compute",
                      "probabilities": {"compute": 0.91, "other": 0.09},
                      "confidence": 0.86
                    },
                    "quality": {
                      "type": "score",
                      "score": 1.8,
                      "legend": {"0": "Missing essentials", "1": "Usable", "2": "Complete"},
                      "probabilities": {"0": 0.0, "1": 0.2, "2": 0.8},
                      "confidence": 0.75
                    }
                  },
                  "usage": {"input_tokens": 296, "output_tokens": 34}
                }
                """.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
