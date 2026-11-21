package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Predicate;

/** A compiled immutable rule in one {@link RuleIndex} snapshot. */
public record Rule(int ordinal, RuleDefinition definition, Predicate<JsonNode> predicate) {

    public Rule(int ordinal, String id, Condition condition, Predicate<JsonNode> predicate, AlertSpec alert) {
        this(ordinal, new RuleDefinition(id, condition, alert), predicate);
    }

    public String id() { return definition.id(); }
    public Condition condition() { return definition.condition(); }
    public AlertSpec alert() { return definition.alert(); }

    public Rule {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Rule ordinal must not be negative");
        }
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(predicate, "predicate");
    }
}
