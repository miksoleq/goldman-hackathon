package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldman.alerting.contracts.RuleCreated;
import java.util.UUID;
import java.util.Objects;

/** The matching-relevant projection of a validated {@code rules.definition} row. */
public record RuleDefinition(String id, Condition condition, AlertSpec alert, String name, int priority,
                             boolean stopProcessing, Deduplication dedup) {
    public RuleDefinition(String id, Condition condition, AlertSpec alert) {
        this(id, condition, alert, id, 100, false, Deduplication.DEFAULT);
    }
    public RuleDefinition(String id, Condition condition) { this(id, condition, null); }

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
        var rule = parseDefinition(id, definition);
        return new RuleDefinition(id, rule.condition(),
                AlertSpec.compile(id, event.name(), event.teamIds(), event.userIds(), definition, event.runbook()),
                event.name(), event.priority(), rule.stopProcessing(), rule.dedup());
    }

    /**
     * Reads only the fields used by matching. The owning service should first
     * validate the complete document against rule-definition.v1.schema.json.
     */
    public static RuleDefinition fromV1(String id, JsonNode definition) {
        var rule = parseDefinition(id, definition);
        for (var key : java.util.List.of("title", "body", "severity")) {
            if (definition.has(key)) RuleTemplate.compile(definition.get(key).textValue(), id, id);
        }
        return rule;
    }

    private static RuleDefinition parseDefinition(String id, JsonNode definition) {
        var condition = RuleSyntax.parse(definition);
        var stop = definition.path("stopProcessing");
        return new RuleDefinition(id, condition, null, id, 100,
                stop.asBoolean(false), Deduplication.compile(definition.path("dedup")));
    }
}
