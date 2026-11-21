package com.goldman.alerting.control.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.control.db.enums.ActionStatus;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
class HttpAction {
    record Outcome(ActionStatus status, JsonNode result) {}
    private static final Pattern SLOT = Pattern.compile("\\{\\{params\\.([A-Za-z][A-Za-z0-9_]*)}}");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;

    HttpAction(ObjectMapper json) {
        this.json = json;
    }

    static URI target(JsonNode template, Map<String, String> params) {
        String url = template.path("url").asText();
        var slots = SLOT.matcher(url);
        var skeleton = URI.create(slots.replaceAll("value"));
        int pathStart = url.indexOf('/', url.indexOf("://") + 3);
        if (skeleton.getHost() == null || !java.util.Set.of("http", "https").contains(skeleton.getScheme())
                || skeleton.getRawUserInfo() != null || skeleton.getRawQuery() != null || skeleton.getFragment() != null
                || pathStart < 0 || url.substring(0, pathStart).contains("{")
                || !java.util.Set.of("GET", "POST").contains(template.path("method").asText())) {
            throw new IllegalArgumentException("Expected a fixed HTTP integration and GET or POST method");
        }
        String expanded = slots.replaceAll(match -> {
            String value = params.get(match.group(1));
            if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                    || value.contains("/") || value.contains("\\")) {
                throw new IllegalArgumentException("Invalid path parameter: " + match.group(1));
            }
            return java.util.regex.Matcher.quoteReplacement(UriUtils.encode(value, StandardCharsets.UTF_8));
        });
        return URI.create(expanded);
    }

    Outcome execute(JsonNode template, Map<String, String> params, UUID executionId) {
        boolean read = "GET".equals(template.path("method").asText());
        int timeoutMs = Math.clamp(template.path("timeoutMs").asInt(5000), 1, 10000);
        var builder = HttpRequest.newBuilder(target(template, params))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(timeoutMs));
        if (!read) builder.header("Idempotency-Key", executionId.toString()).POST(HttpRequest.BodyPublishers.noBody());
        var pending = client.sendAsync(builder.build(), info -> boundedBody());
        try {
            var response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            var result = json.createObjectNode().put("httpStatus", response.statusCode());
            String body = response.body();
            if (body.length() <= 4096) {
                try {
                    result.set("response", json.readTree(body));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    result.put("response", body);
                }
            } else {
                result.put("response", body.substring(0, 4096));
            }
            int code = response.statusCode();
            if (code >= 200 && code < 300) return new Outcome(ActionStatus.succeeded, result);
            if (!read && (code >= 500 || code == 408)) {
                result.put("message", "Outcome unknown. Check workflow status.");
                return new Outcome(ActionStatus.unknown, result);
            }
            return new Outcome(ActionStatus.failed, result);
        } catch (ExecutionException | TimeoutException | InterruptedException e) {
            pending.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Outcome(read ? ActionStatus.failed : ActionStatus.unknown,
                    json.createObjectNode().put("message", read ? "Could not read workflow status."
                            : "Outcome unknown. Check workflow status."));
        }
    }

    private static HttpResponse.BodySubscriber<String> boundedBody() {
        var bytes = new ByteArrayOutputStream();
        var subscriber = HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(data ->
                bytes.write(data, 0, Math.min(4097 - bytes.size(), data.length))));
        return HttpResponse.BodySubscribers.mapping(subscriber, ignored -> bytes.toString(StandardCharsets.UTF_8));
    }
}
