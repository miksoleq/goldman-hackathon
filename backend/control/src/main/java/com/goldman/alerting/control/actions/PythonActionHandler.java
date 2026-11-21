package com.goldman.alerting.control.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.control.db.enums.ActionStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PythonActionHandler {
    private static final Duration DEADLINE = Duration.ofSeconds(6);
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(DEADLINE).build();

    PythonActionHandler(ObjectMapper json, @Value("${script-runner.url:http://script-runner:8001/execute}") URI endpoint) {
        this.json = json;
        this.endpoint = endpoint;
    }

    HttpAction.Outcome execute(JsonNode template, JsonNode context) {
        var job = json.createObjectNode().put("code", template.path("code").textValue())
                .put("timeoutMs", template.path("timeoutMs").asInt(3000));
        job.set("context", context);
        var request = HttpRequest.newBuilder(endpoint).timeout(DEADLINE).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(job.toString())).build();
        long start = System.nanoTime();
        var pending = client.sendAsync(request, ignored -> HttpAction.boundedBody(512 * 1024));
        try {
            var response = pending.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) return failure("Runner returned HTTP " + response.statusCode(), start);
            var body = json.readTree(response.body());
            if (body == null || !body.path("exitCode").isIntegralNumber() || !body.has("result")
                    || !body.path("stdout").isTextual() || !body.path("stderr").isTextual()
                    || !body.path("durationMs").isIntegralNumber()) return failure("Invalid runner response", start);
            return switch (body.path("status").asText()) {
                case "SUCCESS" -> new HttpAction.Outcome(ActionStatus.succeeded, body);
                case "ERROR", "TIMEOUT" -> new HttpAction.Outcome(ActionStatus.failed, body);
                default -> failure("Unknown runner status", start);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Runner request interrupted", start);
        } catch (IOException | ExecutionException | TimeoutException e) {
            return failure("Runner unavailable or response deadline exceeded", start);
        } finally {
            pending.cancel(true);
        }
    }

    private HttpAction.Outcome failure(String message, long start) {
        return new HttpAction.Outcome(ActionStatus.failed, json.createObjectNode().put("status", "ERROR")
                .putNull("exitCode").putNull("result").put("stdout", "").put("stderr", message)
                .put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
    }
}
