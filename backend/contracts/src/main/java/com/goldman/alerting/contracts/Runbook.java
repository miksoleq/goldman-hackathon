package com.goldman.alerting.contracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

public record Runbook(UUID id, String name, String format, String content, List<Action> actions) {
    public Runbook {
        Objects.requireNonNull(id, "runbook.id");
        Objects.requireNonNull(name, "runbook.name");
        Objects.requireNonNull(format, "runbook.format");
        Objects.requireNonNull(content, "runbook.content");
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public Runbook(UUID id, String name, String format, String content) {
        this(id, name, format, content, List.of());
    }

    public record Action(String type, Map<String, String> params) {
        public Action {
            params = Map.copyOf(params);
        }
    }
}
