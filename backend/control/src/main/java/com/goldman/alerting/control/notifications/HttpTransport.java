package com.goldman.alerting.control.notifications;

import static com.goldman.alerting.control.notifications.DeliveryQueue.Outcome.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.goldman.alerting.control.notifications.DeliveryQueue.Outcome;
import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class HttpTransport implements ChannelTransport {
    private static final Set<String> OPTIONS = Set.of("recipientField", "endpoint", "body", "idempotencyHeader",
            "successStatus", "successBoolean");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final HttpEndpoints endpoints;

    HttpTransport(ObjectMapper json, HttpEndpoints endpoints) {
        this.json = json;
        this.endpoints = endpoints;
    }

    @Override public String type() { return "http"; }

    @Override public void validate(JsonNode config) {
        config.fieldNames().forEachRemaining(key -> {
            if (!OPTIONS.contains(key)) throw new IllegalArgumentException("Unknown HTTP option: " + key);
        });
        endpoints.require(config.path("endpoint").asText());
        AlertBodyTemplate.validate(config.get("body"));
        if (config.has("idempotencyHeader") && !config.path("idempotencyHeader").asText().matches("(?i)(X-)?(Idempotency-Key|Request-Id)"))
            throw new IllegalArgumentException("Invalid idempotency header");
        if (config.has("successBoolean") && !config.path("successBoolean").asText().matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Expected a boolean response field name");
        if (config.has("successStatus")) {
            var statuses = config.get("successStatus");
            if (!statuses.isArray() || statuses.isEmpty()) throw new IllegalArgumentException("Expected successStatus list");
            for (var status : statuses) {
                if (!status.isInt() || status.intValue() < 200 || status.intValue() >= 300)
                    throw new IllegalArgumentException("Expected a 2xx success status");
            }
        }
    }

    @Override public Prepared prepare(JsonNode config, Alert alert) {
        ObjectNode options = config.deepCopy();
        options.remove("body");
        options.remove("recipientField");
        var endpoint = endpoints.require(options.get("endpoint").asText());
        options.put("targetHash", targetHash(endpoint));
        return new Prepared(AlertBodyTemplate.render(config.get("body"), alert).toString(), options);
    }

    @Override public Outcome send(DeliveryQueue.Job delivery) {
        JsonNode options = delivery.options();
        var endpoint = endpoints.require(options.path("endpoint").asText());
        if (options.has("targetHash") && !targetHash(endpoint).equals(options.get("targetHash").asText())) {
            return PERMANENT_FAILURE;
        }
        var request = HttpRequest.newBuilder(endpoint.url()).timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(delivery.message()));
        if (endpoint.authorization() != null && !endpoint.authorization().isBlank())
            request.header("Authorization", endpoint.authorization());
        if (options.has("idempotencyHeader"))
            request.header(options.get("idempotencyHeader").asText(), delivery.id().toString());
        try {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 500 || status == 429 || status == 408) return TRANSIENT_FAILURE;
            if (status < 200 || status >= 300) return PERMANENT_FAILURE;
            if (options.has("successStatus")) {
                boolean accepted = false;
                for (var code : options.get("successStatus")) accepted |= code.intValue() == status;
                if (!accepted) return PERMANENT_FAILURE;
            }
            if (options.has("successBoolean")) {
                var body = json.readTree(response.body());
                var ok = body == null ? null : body.get(options.get("successBoolean").asText());
                if (ok == null || !ok.isBoolean() || !ok.booleanValue()) return PERMANENT_FAILURE;
            }
            return SENT;
        } catch (IOException error) {
            return TRANSIENT_FAILURE;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return TRANSIENT_FAILURE;
        }
    }

    private static String targetHash(HttpEndpoints.Endpoint endpoint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.url().toASCIIString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
