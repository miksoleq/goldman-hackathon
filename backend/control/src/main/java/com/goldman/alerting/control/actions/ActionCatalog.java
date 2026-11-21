package com.goldman.alerting.control.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.goldman.alerting.contracts.Runbook.Action;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.JSONB;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.goldman.alerting.control.db.Tables.AUDIT_LOG;
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
        if ("python".equals(row.getKind())) {
            var settings = (ObjectNode) template.deepCopy();
            if (action.code() != null) settings.put("code", action.code());
            if (action.timeoutMs() != null) settings.put("timeoutMs", action.timeoutMs());
            validatePython(settings);
            return new Definition("python", Objects.requireNonNullElse(row.getDescription(), "Python Script"),
                    settings, approval(action.approval(), row.getApprovalRequired()), row.getAutoExecute());
        }
        if (action.code() != null || action.approval() != null || action.timeoutMs() != null) {
            throw new IllegalArgumentException("Script settings require a Python action");
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

    private static void validatePython(JsonNode settings) {
        var code = settings.path("code");
        if (!code.isTextual() || code.textValue().isBlank() || code.textValue().length() > 65536
                || code.textValue().indexOf(0) >= 0) {
            throw new IllegalArgumentException("Expected Python code of at most 65536 characters");
        }
        var timeout = settings.path("timeoutMs");
        if (!timeout.isMissingNode() && (!timeout.isIntegralNumber() || !timeout.canConvertToInt() || timeout.intValue() <= 0)) {
            throw new IllegalArgumentException("Expected positive timeoutMs");
        }
    }

    private static boolean approval(String policy, boolean fallback) {
        if (policy == null) return fallback;
        return switch (policy) {
            case "required" -> true;
            case "none" -> false;
            default -> throw new IllegalArgumentException("Expected approval required or none");
        };
    }

    @Transactional
    public void registerPython(String name, ObjectNode spec, String actor) {
        spec.fieldNames().forEachRemaining(key -> {
            if (!Set.of("type", "code", "approval", "timeoutMs").contains(key)) {
                throw new IllegalArgumentException("Unknown ActionType key: " + key);
            }
        });
        if (!"python".equals(spec.path("type").asText())) throw new IllegalArgumentException("Expected type python");
        validatePython(spec);
        var template = mapper.createObjectNode().put("code", spec.path("code").textValue())
                .put("timeoutMs", Math.min(5000, spec.path("timeoutMs").asInt(3000)));
        template.putArray("params");
        boolean required = approval(spec.has("approval") ? spec.get("approval").asText() : null, true);
        int inserted = db.insertInto(ACTION_TYPES).set(ACTION_TYPES.NAME, name).set(ACTION_TYPES.KIND, "python")
                .set(ACTION_TYPES.DESCRIPTION, name).set(ACTION_TYPES.TEMPLATE, JSONB.valueOf(template.toString()))
                .set(ACTION_TYPES.APPROVAL_REQUIRED, required).set(ACTION_TYPES.AUTO_EXECUTE, true)
                .onConflictDoNothing().execute();
        if (inserted == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Action type already exists");
        db.insertInto(AUDIT_LOG).set(AUDIT_LOG.ENTITY_TYPE, "action_type").set(AUDIT_LOG.ENTITY_ID, name)
                .set(AUDIT_LOG.ACTOR, actor).set(AUDIT_LOG.ACTION, "action_type.created").execute();
    }
}
