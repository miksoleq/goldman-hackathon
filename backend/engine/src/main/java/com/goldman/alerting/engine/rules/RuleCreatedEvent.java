package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Control's rule-created envelope for a rule sent to Engine for indexing. */
public record RuleCreatedEvent(String ruleId, int version, boolean enabled, JsonNode definition, String name, List<UUID> teams, List<UUID> users) {
    public RuleCreatedEvent(String ruleId, int version, boolean enabled, JsonNode definition) {
        this(ruleId, version, enabled, definition, ruleId, List.of(), List.of());
    }

    public RuleCreatedEvent {
        if (ruleId == null || ruleId.isBlank()) {
            throw new InvalidRuleDefinitionException("Rule event requires a non-blank ruleId");
        }
        Objects.requireNonNull(definition, "definition");
        if (!definition.isObject()) {
            throw new InvalidRuleDefinitionException("Rule event definition must be an object");
        }
        definition = definition.deepCopy();
        teams = List.copyOf(teams);
        users = List.copyOf(users);
    }

    public JsonNode definition() {
        return definition.deepCopy();
    }

    public RuleDefinition toRuleDefinition() {
        try { UUID.fromString(ruleId); }
        catch (IllegalArgumentException e) { throw new InvalidRuleDefinitionException("Expected UUID ruleId"); }
        var rule = RuleDefinition.fromV1(ruleId, definition);
        return new RuleDefinition(ruleId, rule.condition(), AlertSpec.compile(ruleId, name, teams, users, definition));
    }

    public static RuleCreatedEvent fromJson(String json, ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        final JsonNode event;
        try {
            event = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new InvalidRuleDefinitionException("Rule event must be valid JSON");
        }
        if (event == null || !event.isObject()) {
            throw new InvalidRuleDefinitionException("Rule event must be a JSON object");
        }

        JsonNode type = event.get("type");
        JsonNode ruleId = event.get("ruleId");
        JsonNode enabled = event.get("enabled");
        JsonNode definition = event.get("definition");
        if (type == null || !type.isTextual() || !"rule.created".equals(type.textValue())) {
            throw new InvalidRuleDefinitionException("Rule event type must be rule.created");
        }
        if (ruleId == null || !ruleId.isTextual() || ruleId.textValue().isBlank()) {
            throw new InvalidRuleDefinitionException("Rule event requires a non-blank string ruleId");
        }
        if (enabled == null || !enabled.isBoolean()) {
            throw new InvalidRuleDefinitionException("Rule event requires a boolean enabled field");
        }
        if (definition == null || !definition.isObject()) {
            throw new InvalidRuleDefinitionException("Rule event definition must be an object");
        }
        JsonNode version = event.get("version");
        if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1)) {
            throw new InvalidRuleDefinitionException("Rule event version must be a positive integer");
        }
        return new RuleCreatedEvent(ruleId.textValue(), version == null ? 1 : version.intValue(), enabled.booleanValue(), definition,
                event.path("name").asText(ruleId.textValue()), recipients(event, "teamIds"), recipients(event, "userIds"));
    }
    private static List<UUID> recipients(JsonNode event, String key) {
        if (!event.has(key)) return List.of();
        if (!event.get(key).isArray()) throw new InvalidRuleDefinitionException("Expected array: " + key);
        List<UUID> ids = new ArrayList<>();
        for (var node : event.get(key)) ids.add(UUID.fromString(node.asText()));
        return ids;
    }
}
