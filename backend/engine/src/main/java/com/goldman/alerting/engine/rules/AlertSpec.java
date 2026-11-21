package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import com.goldman.alerting.contracts.Runbook;
import java.util.function.Function;

/** Alert routing and rendering belong to the same snapshot as the predicate. */
public record AlertSpec(List<UUID> teams, List<UUID> users, Function<JsonNode,String> title,
                        Function<JsonNode,String> body, Function<JsonNode,String> severity, Function<JsonNode,Runbook> runbook, boolean autoRunbook) {
    public AlertSpec {
        teams = List.copyOf(teams);
        users = List.copyOf(users);
    }

    static AlertSpec compile(String id, String name, List<UUID> teams, List<UUID> users, JsonNode definition, Runbook book, UUID pinnedId) {
        Function<JsonNode,Runbook> render = event -> null;
        if (book != null) {
            var content = "url".equals(book.format()) ? (Function<JsonNode,String>) event -> book.content()
                    : RuleTemplate.compile(book.content(), id, name);
            render = event -> new Runbook(book.id(), book.name(), book.format(), content.apply(event));
        }
        return new AlertSpec(teams, users, RuleTemplate.compile(definition.path("title").asText(), id, name),
                RuleTemplate.compile(definition.path("body").asText(""), id, name),
                RuleTemplate.compile(definition.path("severity").asText("medium"), id, name).andThen(AlertSpec::level), render,
                pinnedId == null && book == null && "auto".equals(definition.path("runbookMode").asText("auto")));
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
}
