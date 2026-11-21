package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Parses and bounds the {@code match} subtree from rule-definition v1. */
public final class ConditionParser {

    private static final int MAX_DEPTH = 10;
    private static final int MAX_NODES = 100;
    private static final int MAX_CHILDREN = 50;
    private static final Set<String> LOGICAL_KEYS = Set.of("all", "any", "not");
    private static final Set<String> LEAF_KEYS = Set.of("path", "op", "value", "ignoreCase");

    private ConditionParser() {
    }

    public static Condition parse(JsonNode match) {
        Counter counter = new Counter();
        return parseNode(match, 1, counter);
    }

    private static Condition parseNode(JsonNode node, int depth, Counter counter) {
        if (depth > MAX_DEPTH) {
            throw new InvalidRuleDefinitionException("Condition tree exceeds depth 10");
        }
        if (++counter.nodes > MAX_NODES) {
            throw new InvalidRuleDefinitionException("Condition tree exceeds 100 nodes");
        }
        if (node == null || !node.isObject()) {
            throw new InvalidRuleDefinitionException("Each condition node must be an object");
        }

        int controlKeys = present(node, "all") + present(node, "any") + present(node, "not") + present(node, "path");
        if (controlKeys != 1) {
            throw new InvalidRuleDefinitionException(
                    "Condition node must contain exactly one of: all, any, not, path"
            );
        }

        if (node.has("all")) {
            requireOnlyKeys(node, LOGICAL_KEYS, "all condition");
            return new Condition.All(parseChildren(node.get("all"), depth, counter, "all"));
        }
        if (node.has("any")) {
            requireOnlyKeys(node, LOGICAL_KEYS, "any condition");
            return new Condition.Any(parseChildren(node.get("any"), depth, counter, "any"));
        }
        if (node.has("not")) {
            requireOnlyKeys(node, LOGICAL_KEYS, "not condition");
            return new Condition.Not(parseNode(node.get("not"), depth + 1, counter));
        }
        return parseLeaf(node);
    }

    private static List<Condition> parseChildren(JsonNode children, int depth, Counter counter, String operator) {
        if (children == null || !children.isArray() || children.isEmpty() || children.size() > MAX_CHILDREN) {
            throw new InvalidRuleDefinitionException(operator + " expects an array of 1 to 50 conditions");
        }
        List<Condition> parsed = new ArrayList<>(children.size());
        for (JsonNode child : children) {
            parsed.add(parseNode(child, depth + 1, counter));
        }
        return List.copyOf(parsed);
    }

    private static Condition parseLeaf(JsonNode node) {
        requireOnlyKeys(node, LEAF_KEYS, "leaf condition");
        JsonNode pathNode = node.get("path");
        JsonNode operatorNode = node.get("op");
        if (pathNode == null || !pathNode.isTextual()) {
            throw new InvalidRuleDefinitionException("Leaf path must be a string");
        }
        if (operatorNode == null || !operatorNode.isTextual()) {
            throw new InvalidRuleDefinitionException("Leaf op must be a string");
        }

        Condition.Operator operator = parseOperator(operatorNode.textValue());
        JsonNode value = node.get("value");
        if (operator == Condition.Operator.EXISTS) {
            if (value == null) {
                value = BooleanNode.TRUE;
            } else if (!value.isBoolean()) {
                throw new InvalidRuleDefinitionException("exists value must be a boolean");
            }
        } else if (value == null) {
            throw new InvalidRuleDefinitionException(operator + " requires value");
        }

        JsonNode ignoreCaseNode = node.get("ignoreCase");
        if (ignoreCaseNode != null && !ignoreCaseNode.isBoolean()) {
            throw new InvalidRuleDefinitionException("ignoreCase must be a boolean");
        }
        boolean ignoreCase = ignoreCaseNode != null && ignoreCaseNode.booleanValue();
        CompiledPath compiledPath = CompiledPath.compile(pathNode.textValue());
        return new Condition.Leaf(compiledPath, operator, value, ignoreCase);
    }

    private static Condition.Operator parseOperator(String source) {
        return switch (source) {
            case "exists" -> Condition.Operator.EXISTS;
            case "eq" -> Condition.Operator.EQ;
            case "ne" -> Condition.Operator.NEQ;
            case "in" -> Condition.Operator.IN;
            case "nin" -> Condition.Operator.NIN;
            case "gt" -> Condition.Operator.GT;
            case "gte" -> Condition.Operator.GTE;
            case "lt" -> Condition.Operator.LT;
            case "lte" -> Condition.Operator.LTE;
            case "contains" -> Condition.Operator.CONTAINS;
            case "startsWith" -> Condition.Operator.STARTS_WITH;
            case "endsWith" -> Condition.Operator.ENDS_WITH;
            case "jsonSchema" -> Condition.Operator.JSON_SCHEMA;
            case "regex" -> Condition.Operator.REGEX;
            default -> throw new InvalidRuleDefinitionException("Unsupported rule operator: " + source);
        };
    }

    private static void requireOnlyKeys(JsonNode node, Set<String> allowed, String description) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new InvalidRuleDefinitionException("Unknown key in " + description + ": " + name);
            }
        }
    }

    private static int present(JsonNode node, String name) {
        return node.has(name) ? 1 : 0;
    }

    private static final class Counter {
        private int nodes;
    }
}
