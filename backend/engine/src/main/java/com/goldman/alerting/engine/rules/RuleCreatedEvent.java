package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Control's Kafka envelope for a rule sent to Engine for indexing. */
public record RuleCreatedEvent(String ruleId, boolean enabled, JsonNode definition) {

    public RuleCreatedEvent {
        if (ruleId == null || ruleId.isBlank()) {
            throw new InvalidRuleDefinitionException("Rule event requires a non-blank ruleId");
        }
        Objects.requireNonNull(definition, "definition");
        if (!definition.isObject()) {
            throw new InvalidRuleDefinitionException("Rule event definition must be an object");
        }
        definition = definition.deepCopy();
    }

    public JsonNode definition() {
        return definition.deepCopy();
    }

    public RuleDefinition toRuleDefinition() {
        return RuleDefinition.fromV1(ruleId, definition);
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
        return new RuleCreatedEvent(ruleId.textValue(), enabled.booleanValue(), definition);
    }
}
