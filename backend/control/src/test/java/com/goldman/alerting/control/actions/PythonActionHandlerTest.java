package com.goldman.alerting.control.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.control.db.enums.ActionStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PythonActionHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private HttpServer server;
    private PythonActionHandler handler;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/execute", exchange -> {
            try (exchange) {
                request.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        handler = new PythonActionHandler(json, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/execute"));
    }

    @AfterEach void close() { server.stop(0); }

    @ParameterizedTest @ValueSource(strings = {"SUCCESS", "TIMEOUT", "ERROR"})
    void passesContextAndPreservesRunnerOutcome(String status) throws Exception {
        var context = json.readTree("{\"event\":{},\"alert\":{},\"previousStepResult\":{\"status\":\"UP\"}}");
        var code = "def run(ctx): return ctx['previousStepResult']";
        var body = json.createObjectNode().put("status", status).put("exitCode", status.equals("SUCCESS") ? 0 : 1)
                .put("stdout", "read").put("stderr", "").put("durationMs", 42);
        body.set("result", status.equals("SUCCESS") ? context.get("previousStepResult") : json.nullNode());
        response.set(body.toString());
        var outcome = handler.execute(json.createObjectNode().put("code", code), context);
        assertEquals(body, outcome.result());
        assertEquals(status.equals("SUCCESS") ? ActionStatus.succeeded : ActionStatus.failed, outcome.status());
        var job = json.readTree(request.get());
        assertEquals(context, job.path("context"));
        assertEquals(code, job.path("code").asText());
        assertEquals(3000, job.path("timeoutMs").asInt());
    }

    @Test void malformedProviderDataBecomesAnAuditableFailure() {
        response.set("{}");
        var outcome = handler.execute(json.createObjectNode().put("code", "pass"), json.createObjectNode());
        assertEquals(ActionStatus.failed, outcome.status());
        assertEquals("ERROR", outcome.result().path("status").asText());
        assertTrue(outcome.result().path("stderr").asText().contains("Invalid runner response"));
    }
}
