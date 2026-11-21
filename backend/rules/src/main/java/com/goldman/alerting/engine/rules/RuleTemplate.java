package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.*;
import java.util.function.Function;

public final class RuleTemplate {
    private RuleTemplate() {}

    @FunctionalInterface
    public interface Renderer {
        String render(JsonNode event, String ruleId, String ruleName);
    }

    public static Function<JsonNode,String> compile(String text, String id, String name) {
        Renderer renderer = compile(text);
        return event -> renderer.render(event, id, name);
    }

    public static Renderer compile(String text) {
        return compile(text, false);
    }

    /** Action parameters become unavailable if any required substitution is missing. */
    public static Renderer compileParameter(String text) {
        return compile(text, true);
    }

    private static Renderer compile(String text, boolean required) {
        List<Renderer> parts = new ArrayList<>();
        int start = 0;
        for (int open; (open = text.indexOf("{{", start)) >= 0;) {
            boolean escaped = open > start && text.charAt(open - 1) == '\\';
            String literal = text.substring(start, escaped ? open - 1 : open);
            parts.add((event, id, name) -> literal);
            if (escaped) {
                parts.add((event, id, name) -> "{{");
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
            parts.add((event, id, name) -> {
                List<JsonNode> nodes = new ArrayList<>();
                if (path != null) path.forEachValue(event, nodes::add);
                else nodes.add(TextNode.valueOf(ref.equals("rule.id") ? id : name));
                if (required) nodes.removeIf(JsonNode::isNull);
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
                return required && result.isBlank() ? null : result;
            });
            start = end + 2;
        }
        String tail = text.substring(start);
        if (parts.isEmpty()) return (event, id, name) -> tail;
        return (event, id, name) -> {
            var out = new StringBuilder();
            for (var part : parts) {
                String value = part.render(event, id, name);
                if (value == null) return "";
                out.append(value);
            }
            return out.append(tail).toString();
        };
    }

    private static String join(List<JsonNode> nodes, boolean json) {
        var out = new StringJoiner(", ");
        for (var node : nodes) out.add(!json && node.isTextual() ? node.textValue() : node.toString());
        return out.toString();
    }
}
