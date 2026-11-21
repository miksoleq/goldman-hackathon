package com.goldman.alerting.control.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.goldman.alerting.contracts.Runbook.Action;
import java.util.HashSet;
import java.util.Objects;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import static com.goldman.alerting.control.db.Tables.ACTION_TYPES;

@Component
public class ActionCatalog {
    public record Definition(String kind, String title, JsonNode template, boolean approvalRequired, boolean autoExecute) {
        boolean automatic() { return autoExecute && !approvalRequired; }
    }

    private final DSLContext db;
    private final ObjectMapper mapper;

    public ActionCatalog(DSLContext db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Definition resolve(Action action) {
        var row = db.selectFrom(ACTION_TYPES).where(ACTION_TYPES.NAME.eq(action.type())).fetchOptional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown action: " + action.type()));
        JsonNode template;
        try {
            template = mapper.readTree(row.getTemplate().data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid catalog JSON", e);
        }
        var parameters = template.path("params");
        if (!parameters.isArray()) throw new IllegalArgumentException("Action is not configured for runbooks: " + action.type());
        var required = new HashSet<String>();
        parameters.forEach(param -> required.add(param.asText()));
        if (!required.equals(action.params().keySet())) {
            throw new IllegalArgumentException(action.type() + " expects parameters " + required);
        }
        if ("http".equals(row.getKind())) {
            HttpAction.target(template, action.params().keySet().stream()
                    .collect(java.util.stream.Collectors.toMap(key -> key, key -> "sample")));
        } else if (!"escalate".equals(row.getKind()) || !required.equals(java.util.Set.of("team"))) {
            throw new IllegalArgumentException("Unsupported action configuration: " + action.type());
        }
        return new Definition(row.getKind(), Objects.requireNonNullElse(row.getDescription(), action.type()),
                template, row.getApprovalRequired(), row.getAutoExecute());
    }
}
