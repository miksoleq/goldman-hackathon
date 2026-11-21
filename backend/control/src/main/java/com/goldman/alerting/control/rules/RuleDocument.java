package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.re2j.PatternSyntaxException;
import com.networknt.schema.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

@Component
class RuleDocument {
    record Draft(String name, boolean enabled, int priority, List<String> teams, List<String> users,
                 String runbook, InlineBook inlineBook, ObjectNode definition) {}

    record InlineBook(String format, String content) {}

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final JsonSchema schema;
    private final Pattern path;

    RuleDocument() throws IOException {
        try (var input = getClass().getResourceAsStream("/schemas/rule-definition.v1.schema.json")) {
            JsonNode spec = new ObjectMapper().readTree(input);
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(spec);
            path = Pattern.compile(spec.at("/$defs/path/pattern").textValue());
        }
    }

    Draft parse(String source) {
        JsonNode root;
        try {
            root = yaml.readTree(source);
        } catch (IOException e) {
            throw invalid("Invalid YAML: expected one document with unique keys");
        }
        require(root instanceof ObjectNode, "Expected a rule object");
        ObjectNode def = ((ObjectNode) root).deepCopy();
        String name = name(def.remove("name"), "name");
        JsonNode enabled = def.remove("enabled");
        require(enabled == null || enabled.isBoolean(), "enabled must be boolean");
        JsonNode priority = def.remove("priority");
        require(priority == null || priority.isIntegralNumber() && priority.canConvertToInt(), "Invalid priority");
        JsonNode route = def.remove("route");
        require(route != null && route.isObject(), "Expected route object");
        route.fieldNames().forEachRemaining(key -> require(Set.of("teams", "users").contains(key), "Unknown route key: " + key));
        var teams = recipients(route, "teams");
        var users = recipients(route, "users");
        require(!teams.isEmpty() || !users.isEmpty(), "Route requires at least one recipient");
        JsonNode runbook = def.remove("runbook");
        InlineBook inline = null;
        String book;
        if (runbook != null && runbook.isObject()) {
            book = name(runbook.get("name"), "runbook.name");
            require(!Set.of("auto", "none").contains(book), "Reserved runbook name");
            String format = name(runbook.get("format"), "runbook.format");
            String content = name(runbook.get("content"), "runbook.content");
            require(runbook.size() == 3 && Set.of("markdown", "text", "url").contains(format), "Invalid runbook");
            if (format.equals("url")) {
                var uri = java.net.URI.create(content);
                require(uri.getHost() != null && Set.of("http", "https").contains(uri.getScheme()), "Expected HTTP(S) URL");
            } else {
                checkTemplate(content);
            }
            inline = new InlineBook(format, content);
        } else {
            book = runbook == null ? "auto" : name(runbook, "runbook");
        }
        require(!def.has("runbookMode"), "Use runbook in the YAML document");
        if (!def.has("v")) def.put("v", 1);
        def.put("runbookMode", book.equals("none") ? "none" : "auto");
        checkTree(def.path("match"), 1, new int[1]);
        var errors = schema.validate(def);
        if (!errors.isEmpty()) throw invalid(errors.iterator().next().getMessage());
        if (!def.has("severity")) def.put("severity", "medium");
        if (!def.has("body")) def.put("body", "");
        if (!def.has("stopProcessing")) def.put("stopProcessing", false);
        ObjectNode dedup = def.withObject("/dedup");
        if (!dedup.has("keys")) dedup.putArray("keys");
        if (!dedup.has("windowSec")) dedup.put("windowSec", 60);
        for (String field : List.of("severity", "title", "body")) checkTemplate(def.get(field).textValue());
        return new Draft(name, enabled == null || enabled.booleanValue(), priority == null ? 100 : priority.intValue(),
                teams, users, Set.of("auto","none").contains(book) ? null : book, inline, def);
    }

    private static List<String> recipients(JsonNode route, String key) {
        if (!route.has(key)) return List.of();
        require(route.get(key).isArray(), "Expected route." + key + " array");
        Set<String> names = new LinkedHashSet<>();
        for (var value : route.get(key)) require(names.add(name(value, key)), "Duplicate recipient in " + key);
        return List.copyOf(names);
    }

    private static void checkTree(JsonNode node, int depth, int[] count) {
        require(depth <= 10 && ++count[0] <= 100, "match exceeds depth 10 or 100 nodes");
        for (String key : List.of("all", "any")) {
            for (JsonNode child : node.path(key)) checkTree(child, depth + 1, count);
        }
        if (node.has("not")) checkTree(node.get("not"), depth + 1, count);
        if ("regex".equals(node.path("op").asText()) && node.path("value").isTextual()) {
            String regex = node.get("value").textValue();
            require(regex.codePointCount(0, regex.length()) <= 500, "Regex exceeds 500 characters");
            try {
                com.google.re2j.Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw invalid("Invalid RE2 regex at " + node.path("path").asText());
            }
        }
    }

    private void checkTemplate(String template) {
        int pos = 0;
        while ((pos = template.indexOf("{{", pos)) >= 0) {
            if (pos > 0 && template.charAt(pos - 1) == '\\') {
                pos += 2;
                continue;
            }
            int end = template.indexOf("}}", pos + 2);
            require(end >= 0, "Unclosed template expression");
            String[] parts = template.substring(pos + 2, end).split("\\|", -1);
            String ref = parts[0].trim();
            require(ref.length() <= 300 && (path.matcher(ref).matches() || Set.of("rule.id","rule.name").contains(ref)),
                    "Invalid template reference: " + ref);
            for (int i = 1; i < parts.length; i++) {
                String filter = parts[i].trim();
                require(Set.of("upper","lower","json").contains(filter) || filter.startsWith("default:")
                        || filter.matches("truncate:\\s*[0-9]+"), "Invalid template filter: " + filter);
            }
            pos = end + 2;
        }
    }

    private static String name(JsonNode value, String label) {
        require(value != null && value.isTextual() && !value.textValue().isBlank(), "Expected " + label);
        return value.textValue();
    }

    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static void require(boolean ok, String message) { if (!ok) throw invalid(message); }
}
