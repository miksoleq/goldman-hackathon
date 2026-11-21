package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.*;
import java.util.function.Function;

public final class RuleTemplate {
    private RuleTemplate() {}

    public static Function<JsonNode,String> compile(String text, String id, String name) {
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
            if (end < 0) throw new InvalidRuleDefinitionException("Unclosed alert template");
            String[] expr = text.substring(open + 2, end).split("\\|", -1);
            String ref = expr[0].trim();
            for (int i = 1; i < expr.length; i++) {
                String filter = expr[i].trim();
                if (!(Set.of("upper", "lower", "json").contains(filter) || filter.startsWith("default:")
                        || filter.matches("truncate:\\s*[0-9]+"))) {
                    throw new InvalidRuleDefinitionException("Unknown or invalid template filter: " + filter);
                }
            }
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
