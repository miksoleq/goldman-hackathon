package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

public record Deduplication(List<CompiledPath> keys, int windowSeconds) {
    static final Deduplication DEFAULT = new Deduplication(List.of(), 60);
    private static final Pattern VOLATILE_KEY = Pattern.compile(
            "^(.*[_-])?(ts|time|timestamp|date|datetime|created_?at|updated_?at|id|uuid|request_?id|trace_?id|span_?id|event_?id)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    public Deduplication {
        keys = List.copyOf(keys);
        if (windowSeconds < 1 || windowSeconds > 86400) {
            throw new InvalidRuleDefinitionException("dedup.windowSec must be between 1 and 86400");
        }
    }

    static Deduplication compile(JsonNode node) {
        if (node.isMissingNode()) return DEFAULT;
        if (!node.isObject()) throw new InvalidRuleDefinitionException("dedup must be an object");
        var window = node.path("windowSec");
        if (!window.isMissingNode() && (!window.isIntegralNumber() || !window.canConvertToInt())) {
            throw new InvalidRuleDefinitionException("dedup.windowSec must be an integer");
        }
        var paths = node.path("keys");
        if (!paths.isMissingNode() && (!paths.isArray() || paths.size() > 10)) {
            throw new InvalidRuleDefinitionException("dedup.keys must be an array of up to 10 paths");
        }
        var keys = new ArrayList<CompiledPath>();
        for (var path : paths) {
            var key = CompiledPath.compile(path.textValue());
            if (keys.contains(key)) throw new InvalidRuleDefinitionException("Duplicate dedup key");
            keys.add(key);
        }
        return new Deduplication(keys, window.asInt(60));
    }

    public String fingerprint(String ruleId, JsonNode event) {
        var input = new StringBuilder(ruleId);
        if (keys.isEmpty()) {
            input.append('\u001f');
            var payload = event.path("payload");
            if ("text".equals(event.at("/meta/contentType").asText())) {
                input.append(DIGITS.matcher(payload.path("message").asText()).replaceAll("#"));
            } else {
                appendJson(input, payload, true);
            }
        } else {
            for (var key : keys) {
                input.append('\u001f');
                if (key.hasWildcard()) {
                    var values = new ArrayList<JsonNode>();
                    key.forEachValue(event, values::add);
                    if (values.isEmpty()) input.append("null");
                    else appendArray(input, values, false);
                } else {
                    appendJson(input, key.get(event), false);
                }
            }
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void appendJson(StringBuilder out, JsonNode node, boolean omitVolatile) {
        if (node == null || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (omitVolatile) names.removeIf(name -> VOLATILE_KEY.matcher(name).matches());
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                String name = names.get(i);
                out.append(TextNode.valueOf(name)).append(':');
                appendJson(out, node.get(name), omitVolatile);
            }
            out.append('}');
        } else if (node.isArray()) {
            appendArray(out, node, omitVolatile);
        } else if (node.isNumber()) {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            out.append(node);
        }
    }

    private static void appendArray(StringBuilder out, Iterable<JsonNode> nodes, boolean omitVolatile) {
        out.append('[');
        boolean first = true;
        for (var item : nodes) {
            if (!first) out.append(',');
            appendJson(out, item, omitVolatile);
            first = false;
        }
        out.append(']');
    }
}
