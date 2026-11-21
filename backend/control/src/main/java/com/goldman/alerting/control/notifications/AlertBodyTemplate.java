package com.goldman.alerting.control.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class AlertBodyTemplate {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z.]+)\\s*}}");
    private static final Set<String> FIELDS = Set.of("recipient", "notification.id", "alert.id", "alert.severity",
            "alert.title", "alert.shortTitle", "alert.body", "alert.runbook", "alert.link", "alert.message");

    private AlertBodyTemplate() {}

    static void validate(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("body must be a JSON object");
        transform(body, key -> "");
    }

    static JsonNode render(JsonNode body, ChannelTransport.Alert alert) {
        var event = alert.event();
        return transform(body, key -> switch (key) {
            case "recipient" -> alert.recipient() == null ? "" : alert.recipient();
            case "notification.id" -> alert.notificationId().toString();
            case "alert.id" -> event.alertId().toString();
            case "alert.severity" -> event.severity();
            case "alert.title" -> event.title();
            case "alert.shortTitle" -> {
                String title = event.title();
                yield title.substring(0, title.offsetByCodePoints(0, Math.min(80, title.codePointCount(0, title.length()))));
            }
            case "alert.body" -> event.body() == null ? "" : event.body();
            case "alert.runbook" -> event.runbook() == null ? "" : event.runbook().content();
            case "alert.link" -> alert.link();
            case "alert.message" -> alert.fullText();
            default -> throw new IllegalArgumentException("Unknown alert field: " + key);
        });
    }

    private static JsonNode transform(JsonNode node, Function<String, String> value) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> result.set(e.getKey(), transform(e.getValue(), value)));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(transform(item, value)));
            return result;
        }
        if (!node.isTextual()) return node;
        String template = node.textValue();
        var matcher = TOKEN.matcher(template);
        if (matcher.replaceAll("").contains("{{")) throw new IllegalArgumentException("Invalid template expression");
        return TextNode.valueOf(matcher.replaceAll(match -> {
            String key = match.group(1);
            if (!FIELDS.contains(key)) throw new IllegalArgumentException("Unknown alert field: " + key);
            return java.util.regex.Matcher.quoteReplacement(value.apply(key));
        }));
    }
}
