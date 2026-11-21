package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import com.goldman.alerting.contracts.Runbook;
import java.util.function.Function;

/** Alert routing and rendering belong to the same snapshot as the predicate. */
public record AlertSpec(List<UUID> teams, List<UUID> users, Function<JsonNode,String> title,
                        Function<JsonNode,String> body, Function<JsonNode,String> severity, Function<JsonNode,Runbook> runbook, boolean autoRunbook, List<String> channels) {
    public AlertSpec {
        teams = List.copyOf(teams);
        users = List.copyOf(users);
    }

    static AlertSpec compile(String id, String name, List<UUID> teams, List<UUID> users, JsonNode definition, Runbook book, UUID pinnedId) {
        Function<JsonNode,Runbook> render = event -> null;
        if (book != null) {
            var renderer = compileBook(book);
            render = event -> renderer.render(event, id, name);
        }
        return new AlertSpec(teams, users, RuleTemplate.compile(definition.path("title").asText(), id, name),
                RuleTemplate.compile(definition.path("body").asText(""), id, name),
                RuleTemplate.compile(definition.path("severity").asText("medium"), id, name).andThen(value -> level(value, id)), render,
                pinnedId == null && book == null && "auto".equals(definition.path("runbookMode").asText("auto")),
                definition.has("channels") ? definition.get("channels").valueStream().map(JsonNode::textValue).toList() : null);
    }

    private static String level(String value, String ruleId) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "debug", "trace", "info" -> "info";
            case "low" -> "low";
            case "high", "error", "err" -> "high";
            case "critical", "crit", "fatal", "emergency" -> "critical";
            case "medium", "warn", "warning" -> "medium";
            default -> {
                io.micrometer.core.instrument.Metrics.counter("rules.severity.unmapped", "rule", ruleId).increment();
                yield "medium";
            }
        };
    }

    interface BookRenderer {
        Runbook render(JsonNode event, String ruleId, String ruleName);
    }

    static BookRenderer compileBook(Runbook book) {
        RuleTemplate.Renderer content = "url".equals(book.format())
                ? (event, id, name) -> book.content() : RuleTemplate.compile(book.content());
        var actions = book.actions().stream().map(AlertSpec::compileAction).toList();
        return (event, id, name) -> new Runbook(book.id(), book.name(), book.format(), content.render(event, id, name),
                actions.stream().map(action -> action.render(event, id, name)).toList());
    }

    private interface ActionRenderer {
        Runbook.Action render(JsonNode event, String ruleId, String ruleName);
    }

    private static ActionRenderer compileAction(Runbook.Action action) {
        Map<String, RuleTemplate.Renderer> parameters = new LinkedHashMap<>();
        action.params().forEach((key, value) -> parameters.put(key, RuleTemplate.compileParameter(value)));
        return (event, id, name) -> {
            Map<String, String> rendered = new LinkedHashMap<>();
            parameters.forEach((key, value) -> rendered.put(key, value.render(event, id, name)));
            return new Runbook.Action(action.type(), rendered, action.code(), action.approval(), action.timeoutMs());
        };
    }
}
