package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Predicate;

/** A compiled immutable rule in one {@link RuleIndex} snapshot. */
public record Rule(int ordinal, String id, Condition condition, Predicate<JsonNode> predicate) {

    public Rule {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Rule ordinal must not be negative");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(predicate, "predicate");
    }
}
