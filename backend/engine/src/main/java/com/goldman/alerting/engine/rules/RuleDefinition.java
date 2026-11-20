package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** The matching-relevant projection of a validated {@code rules.definition} row. */
public record RuleDefinition(String id, Condition condition, AlertSpec alert) {
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
        return new RuleDefinition(id, ConditionParser.parse(match));
    }
}
