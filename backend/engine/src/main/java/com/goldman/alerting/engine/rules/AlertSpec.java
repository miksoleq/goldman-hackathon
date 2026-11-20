package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.*;
import java.util.function.Function;

/** Alert routing and rendering belong to the same snapshot as the predicate. */
public record AlertSpec(List<UUID> teams, List<UUID> users, Function<JsonNode,String> title,
                        Function<JsonNode,String> body, Function<JsonNode,String> severity) {
    public AlertSpec {
        teams = List.copyOf(teams);
        users = List.copyOf(users);
    }

    static AlertSpec compile(String id, String name, List<UUID> teams, List<UUID> users, JsonNode definition) {
        return new AlertSpec(teams, users, template(definition.path("title").asText(), id, name),
                template(definition.path("body").asText(""), id, name),
                template(definition.path("severity").asText("medium"), id, name).andThen(AlertSpec::level));
    }

    private static String level(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "debug", "trace", "info" -> "info";
            case "low" -> "low";
            case "high", "error", "err" -> "high";
            case "critical", "crit", "fatal", "emergency" -> "critical";
            default -> "medium";
        };
    }

    private static Function<JsonNode,String> template(String text, String id, String name) {
        List<Function<JsonNode,String>> parts = new ArrayList<>();
        int start = 0;
        for (int open; (open = text.indexOf("{{", start)) >= 0;) {
            boolean escaped = open > start && text.charAt(open - 1) == '\\';
            String literal = text.substring(start, escaped ? open - 1 : open);
            parts.add(event -> literal);
            if (escaped) {
                parts.add(event -> "{{");
                start = open + 2;
                continue;
            }
            int end = text.indexOf("}}", open + 2);
            if (end < 0) throw new IllegalArgumentException("Unclosed alert template");
            String[] expr = text.substring(open + 2, end).split("\\|", -1);
            String ref = expr[0].trim();
            CompiledPath path = ref.equals("rule.id") || ref.equals("rule.name") ? null : CompiledPath.compile(ref);
            parts.add(event -> {
                List<JsonNode> nodes = new ArrayList<>();
                if (path != null) path.forEachValue(event, nodes::add);
                else nodes.add(TextNode.valueOf(ref.equals("rule.id") ? id : name));
                String result = join(nodes, false);
                for (int i = 1; i < expr.length; i++) {
                    String filter = expr[i].trim();
                    if (filter.equals("json")) result = i == 1 ? join(nodes, true) : TextNode.valueOf(result).toString();
                    else if (filter.equals("upper")) result = result.toUpperCase(Locale.ROOT);
                    else if (filter.equals("lower")) result = result.toLowerCase(Locale.ROOT);
                    else if (filter.startsWith("default:") && result.isEmpty()) result = filter.substring(8).trim();
                    else if (filter.startsWith("truncate:")) {
                        var limit = new java.math.BigInteger(filter.substring(9).trim());
                        if (limit.compareTo(java.math.BigInteger.valueOf(result.codePointCount(0,result.length()))) < 0)
                            result = result.substring(0, result.offsetByCodePoints(0, limit.intValueExact())) + "…";
                    }
                }
                return result;
            });
            start = end + 2;
        }
        String tail = text.substring(start);
        if (parts.isEmpty()) return event -> tail;
        return event -> {
            var out = new StringBuilder();
            for (var part : parts) out.append(part.apply(event));
            return out.append(tail).toString();
        };
    }

    private static String join(List<JsonNode> nodes, boolean json) {
        var out = new StringJoiner(", ");
        for (var node : nodes) out.add(!json && node.isTextual() ? node.textValue() : node.toString());
        return out.toString();
    }
}
