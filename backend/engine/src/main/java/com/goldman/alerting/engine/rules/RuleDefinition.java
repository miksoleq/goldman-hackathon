package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldman.alerting.contracts.RuleCreated;
import java.util.UUID;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** The matching-relevant projection of a validated {@code rules.definition} row. */
public record RuleDefinition(String id, Condition condition, AlertSpec alert, String name, int priority,
                             boolean stopProcessing, Deduplication dedup) {
    public RuleDefinition(String id, Condition condition, AlertSpec alert) {
        this(id, condition, alert, id, 100, false, Deduplication.DEFAULT);
    }
    public RuleDefinition(String id, Condition condition) { this(id, condition, null); }

    private static final Set<String> V1_KEYS = Set.of(
            "v", "match", "severity", "title", "body", "dedup", "stopProcessing", "runbookMode"
    );

    public RuleDefinition {
        if (id == null || id.isBlank()) {
            throw new InvalidRuleDefinitionException("Rule id must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
    }

    public static RuleDefinition fromEvent(RuleCreated event) {
        String id = event.ruleId();
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidRuleDefinitionException("Expected UUID ruleId");
        }
        var definition = event.definition();
        var rule = RuleDefinition.fromV1(id, definition);
        return new RuleDefinition(id, rule.condition(),
                AlertSpec.compile(id, event.name(), event.teamIds(), event.userIds(), definition, event.runbook()),
                event.name(), event.priority(), rule.stopProcessing(), rule.dedup());
    }

    /**
     * Reads only the fields used by matching. The owning service should first
     * validate the complete document against rule-definition.v1.schema.json.
     */
    public static RuleDefinition fromV1(String id, JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            throw new InvalidRuleDefinitionException("Rule definition must be a JSON object");
        }
        Iterator<String> names = definition.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!V1_KEYS.contains(name)) {
                throw new InvalidRuleDefinitionException("Unknown rule-definition v1 key: " + name);
            }
        }

        JsonNode version = definition.get("v");
        if (version == null || !version.isIntegralNumber() || version.intValue() != 1) {
            throw new InvalidRuleDefinitionException("Unsupported or missing rule-definition version");
        }
        JsonNode title = definition.get("title");
        if (title == null || !title.isTextual() || title.textValue().isEmpty() || title.textValue().length() > 500) {
            throw new InvalidRuleDefinitionException("Rule definition requires a title of 1 to 500 characters");
        }
        JsonNode match = definition.get("match");
        if (match == null) {
            throw new InvalidRuleDefinitionException("Rule definition requires match");
        }
        var stop = definition.path("stopProcessing");
        if (!stop.isMissingNode() && !stop.isBoolean()) {
            throw new InvalidRuleDefinitionException("stopProcessing must be a boolean");
        }
        return new RuleDefinition(id, ConditionParser.parse(match), null, id, 100,
                stop.asBoolean(false), Deduplication.compile(definition.path("dedup")));
    }
}
