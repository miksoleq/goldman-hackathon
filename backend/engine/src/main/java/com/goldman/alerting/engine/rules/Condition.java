package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Objects;

/** Immutable condition AST supported by the rule-definition v1 format. */
public sealed interface Condition permits Condition.All, Condition.Any, Condition.Not, Condition.Leaf {

    enum Operator {
        EXISTS,
        EQ,
        NEQ,
        IN,
        NIN,
        GT,
        GTE,
        LT,
        LTE,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH,
        REGEX,
        JSON_SCHEMA
    }

    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = immutableChildren(conditions, "all");
        }
    }

    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = immutableChildren(conditions, "any");
        }
    }

    record Not(Condition condition) implements Condition {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }

    record Leaf(CompiledPath path, Operator operator, JsonNode value, boolean ignoreCase) implements Condition {
        public Leaf {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(operator, "operator");
            value = value == null ? null : value.deepCopy();
            validateValue(operator, value);
            if (operator == Operator.JSON_SCHEMA && ignoreCase) {
                throw new InvalidRuleDefinitionException("jsonSchema does not support ignoreCase");
            }
        }

        /** Returns a copy so callers cannot mutate an immutable rule snapshot. */
        public JsonNode value() {
            return value == null ? null : value.deepCopy();
        }
    }

    static All all(Condition... conditions) {
        return new All(List.of(conditions));
    }

    static Any any(Condition... conditions) {
        return new Any(List.of(conditions));
    }

    static Not not(Condition condition) {
        return new Not(condition);
    }

    static Leaf leaf(String path, Operator operator, JsonNode value) {
        return leaf(path, operator, value, false);
    }

    static Leaf leaf(String path, Operator operator, JsonNode value, boolean ignoreCase) {
        return new Leaf(CompiledPath.compile(path), operator, value, ignoreCase);
    }

    static Leaf exists(String path) {
        return exists(path, true);
    }

    static Leaf exists(String path, boolean expectedToExist) {
        return leaf(path, Operator.EXISTS, BooleanNode.valueOf(expectedToExist));
    }

    static Leaf eq(String path, JsonNode value) {
        return leaf(path, Operator.EQ, value);
    }

    static Leaf eq(String path, String value) {
        return eq(path, TextNode.valueOf(value));
    }

    static Leaf neq(String path, JsonNode value) {
        return leaf(path, Operator.NEQ, value);
    }

    static Leaf neq(String path, String value) {
        return neq(path, TextNode.valueOf(value));
    }

    static Leaf in(String path, List<JsonNode> values) {
        return leaf(path, Operator.IN, arrayOf(values));
    }

    static Leaf nin(String path, List<JsonNode> values) {
        return leaf(path, Operator.NIN, arrayOf(values));
    }

    static Leaf gt(String path, JsonNode value) {
        return leaf(path, Operator.GT, value);
    }

    static Leaf gte(String path, JsonNode value) {
        return leaf(path, Operator.GTE, value);
    }

    static Leaf lt(String path, JsonNode value) {
        return leaf(path, Operator.LT, value);
    }

    static Leaf lte(String path, JsonNode value) {
        return leaf(path, Operator.LTE, value);
    }

    static Leaf contains(String path, JsonNode value) {
        return leaf(path, Operator.CONTAINS, value);
    }

    static Leaf startsWith(String path, String value) {
        return leaf(path, Operator.STARTS_WITH, TextNode.valueOf(value));
    }

    static Leaf endsWith(String path, String value) {
        return leaf(path, Operator.ENDS_WITH, TextNode.valueOf(value));
    }

    static Leaf regex(String path, String expression) {
        return leaf(path, Operator.REGEX, TextNode.valueOf(expression));
    }

    private static ArrayNode arrayOf(List<JsonNode> values) {
        Objects.requireNonNull(values, "values");
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> array.add(Objects.requireNonNull(value, "value").deepCopy()));
        return array;
    }

    private static List<Condition> immutableChildren(List<Condition> conditions, String operator) {
        Objects.requireNonNull(conditions, "conditions");
        if (conditions.isEmpty() || conditions.size() > 50) {
            throw new InvalidRuleDefinitionException(
                    operator + " must contain between 1 and 50 conditions"
            );
        }
        return List.copyOf(conditions);
    }

    private static void validateValue(Operator operator, JsonNode value) {
        if (operator == Operator.EXISTS) {
            if (value == null || !value.isBoolean()) {
                throw new InvalidRuleDefinitionException("exists expects a boolean value");
            }
            return;
        }
        if (value == null) {
            throw new InvalidRuleDefinitionException(operator + " requires a value");
        }

        switch (operator) {
            case IN, NIN -> {
                if (!value.isArray() || value.isEmpty() || value.size() > 1000) {
                    throw new InvalidRuleDefinitionException(operator + " expects an array of 1 to 1000 values");
                }
            }
            case GT, GTE, LT, LTE -> {
                if (!value.isNumber()) {
                    throw new InvalidRuleDefinitionException(operator + " expects a numeric rule value");
                }
            }
            case STARTS_WITH, ENDS_WITH -> {
                if (!value.isTextual() || value.textValue().isEmpty()) {
                    throw new InvalidRuleDefinitionException(operator + " expects a non-empty string value");
                }
            }
            case REGEX -> {
                if (!value.isTextual() || value.textValue().isEmpty() || value.textValue().length() > 500) {
                    throw new InvalidRuleDefinitionException("regex expects a string of 1 to 500 characters");
                }
            }
            case JSON_SCHEMA -> {
                if (!value.isObject()) throw new InvalidRuleDefinitionException("jsonSchema expects an object");
            }
            case EQ, NEQ, CONTAINS -> {
                // Any JSON value is valid for these operators.
            }
            case EXISTS -> throw new IllegalStateException("exists is validated above");
        }
    }
}
