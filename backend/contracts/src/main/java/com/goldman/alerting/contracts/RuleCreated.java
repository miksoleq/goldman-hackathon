package com.goldman.alerting.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Version-one outbox and Redis snapshot envelope. */
public record RuleCreated(String type, String ruleId, int version, long configVersion,
                          String name, boolean enabled, int priority, List<UUID> teamIds,
                          List<UUID> userIds, UUID runbookId, JsonNode definition, Runbook runbook) {
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public static final String TYPE = "rule.created";

    public RuleCreated {
        if (!TYPE.equals(type) || ruleId == null || ruleId.isBlank() || version < 1
                || definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("Invalid rule.created envelope");
        }
        definition = definition.deepCopy();
        teamIds = List.copyOf(teamIds);
        userIds = List.copyOf(userIds);
    }

    public JsonNode definition() {
        return definition.deepCopy();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RuleCreated fromTree(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected rule object");
        JsonNode version = node.path("version");
        if (!node.path("enabled").isBoolean() || (!version.isMissingNode()
                && (!version.isIntegralNumber() || !version.canConvertToInt()))) {
            throw new IllegalArgumentException("Invalid enabled or version field");
        }
        String id = node.path("ruleId").textValue();
        JsonNode runbook = node.path("runbookId");
        JsonNode book = node.path("runbook");
        Runbook snapshot = book.isObject() ? SNAPSHOT_MAPPER.convertValue(book, Runbook.class) : null;
        return new RuleCreated(node.path("type").textValue(), id, version.asInt(1),
                node.path("configVersion").asLong(), node.path("name").asText(id),
                node.get("enabled").booleanValue(), node.path("priority").asInt(100),
                recipients(node, "teamIds"), recipients(node, "userIds"),
                runbook.isTextual() ? UUID.fromString(runbook.textValue()) : null, node.get("definition"), snapshot);
    }

    private static List<UUID> recipients(JsonNode node, String field) {
        if (!node.has(field)) return List.of();
        if (!node.get(field).isArray()) throw new IllegalArgumentException("Expected array: " + field);
        var ids = new ArrayList<UUID>();
        for (var value : node.get(field)) ids.add(UUID.fromString(value.asText()));
        return ids;
    }
}
