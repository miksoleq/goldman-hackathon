package com.goldman.alerting.control.actions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.goldman.alerting.contracts.LiveAlert;
import com.goldman.alerting.control.db.enums.ActionStatus;
import com.goldman.alerting.control.db.tables.records.ActionExecutionsRecord;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.goldman.alerting.control.db.Tables.*;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.springframework.http.HttpStatus.*;

@Service
public class RunbookActions {
    public record Step(int stepNo, String type, String title, JsonNode params, ActionStatus status,
                       boolean approvalRequired, boolean automatic, boolean available, String reason, JsonNode result,
                       String kind, String code, String executedBy, String approvedBy) {}
    private final DSLContext db;
    private final ObjectMapper json;
    private final ActionCatalog catalog;
    private final HttpAction http;
    private final PythonActionHandler python;

    RunbookActions(DSLContext db, ObjectMapper json, ActionCatalog catalog, HttpAction http, PythonActionHandler python) {
        this.db = db;
        this.json = json;
        this.catalog = catalog;
        this.http = http;
        this.python = python;
    }

    public void initialize(LiveAlert alert) {
        if (alert.runbook() == null) return;
        int index = 0;
        Integer blockedAt = null;
        for (var action : alert.runbook().actions()) {
            var row = db.newRecord(ACTION_EXECUTIONS);
            row.setLiveAlertId(alert.alertId());
            row.setStepNo(++index);
            row.setAction(action.type());
            row.setTitle(action.type());
            row.setParams(stored(json.valueToTree(action.params())));
            row.setAutomatic(true);
            try {
                var definition = catalog.resolve(action);
                row.setTitle(definition.title());
                row.setKind(definition.kind());
                row.setTemplate(stored(definition.template()));
                row.setApprovalRequired(definition.approvalRequired());
                row.setAutomatic(definition.automatic());
                for (var param : action.params().entrySet()) {
                    if (param.getValue().isBlank()) throw new IllegalArgumentException("Missing parameter: " + param.getKey());
                }
                if ("http".equals(definition.kind())) HttpAction.target(definition.template(), action.params());
                row.setStatus(definition.approvalRequired() ? ActionStatus.pending_approval : ActionStatus.ready);
            } catch (IllegalArgumentException e) {
                row.setStatus(ActionStatus.unavailable);
                row.setComment(e.getMessage());
            }
            if (blockedAt != null) {
                row.setStatus(ActionStatus.skipped);
                row.setComment(stopReason(blockedAt));
            } else if (row.getStatus() == ActionStatus.unavailable) {
                blockedAt = index;
            }
            row.insert();
        }
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ObjectNode detail(UUID id) {
        var payload = db.select(LIVE_ALERT_INBOX.PAYLOAD).from(LIVE_ALERT_INBOX)
                .where(LIVE_ALERT_INBOX.ALERT_ID.eq(id)).fetchOptional(LIVE_ALERT_INBOX.PAYLOAD)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        var detail = (ObjectNode) parse(payload);
        var steps = detail.putArray("actions");
        boolean priorSucceeded = true;
        for (var row : db.selectFrom(ACTION_EXECUTIONS).where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(id))
                .orderBy(ACTION_EXECUTIONS.STEP_NO).fetch()) {
            steps.add(json.valueToTree(view(row, priorSucceeded)));
            priorSucceeded &= row.getStatus() == ActionStatus.succeeded;
        }
        return detail;
    }

    @Transactional
    public Step execute(UUID alertId, int stepNo, boolean confirmed, String actor) {
        // Serialize one step through its bounded integration call. The same persisted key survives rollback.
        var row = db.selectFrom(ACTION_EXECUTIONS).where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)
                .and(ACTION_EXECUTIONS.STEP_NO.eq(stepNo))).forUpdate().fetchOptional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if (Boolean.TRUE.equals(row.getAutomatic())) {
            throw new ResponseStatusException(CONFLICT, "This step is managed by the runbook");
        }
        if (row.getAutomatic() != null && db.fetchExists(ACTION_EXECUTIONS, ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)
                .and(ACTION_EXECUTIONS.STEP_NO.lt(stepNo)).and(ACTION_EXECUTIONS.STATUS.ne(ActionStatus.succeeded)))) {
            throw new ResponseStatusException(CONFLICT, "A previous step has not succeeded");
        }
        if (row.getStatus() == ActionStatus.unavailable) throw new ResponseStatusException(CONFLICT, row.getComment());
        if (row.getApprovalRequired() && !confirmed) throw new ResponseStatusException(CONFLICT, "Confirmation required");
        if (!available(row)) return view(row, true);
        return perform(row, actor);
    }

    // Claim in a short transaction so other Control replicas and the monitor see the running step.
    @Transactional
    public UUID claimNext() {
        var steps = ACTION_EXECUTIONS;
        var prior = steps.as("prior");
        Condition predecessorsSucceeded = notExists(selectOne().from(prior)
                .where(prior.LIVE_ALERT_ID.eq(steps.LIVE_ALERT_ID)).and(prior.STEP_NO.lt(steps.STEP_NO))
                .and(prior.STATUS.ne(ActionStatus.succeeded)));
        var now = OffsetDateTime.now();
        var row = db.selectFrom(steps).where(steps.AUTOMATIC.isTrue()).and(predecessorsSucceeded)
                .and(steps.STATUS.eq(ActionStatus.ready).or(steps.STATUS.eq(ActionStatus.running)
                        .and(steps.STARTED_AT.lt(now.minusSeconds(30)))))
                .orderBy(steps.LIVE_ALERT_ID, steps.STEP_NO).limit(1).forUpdate().skipLocked().fetchOne();
        if (row == null) return null;
        row.setStartedAt(now);
        row.setStatus(ActionStatus.running);
        row.update();
        return row.getId();
    }

    @Transactional
    public void runClaimed(UUID id) {
        var row = db.selectFrom(ACTION_EXECUTIONS).where(ACTION_EXECUTIONS.ID.eq(id))
                .and(ACTION_EXECUTIONS.AUTOMATIC.isTrue()).and(ACTION_EXECUTIONS.STATUS.eq(ActionStatus.running))
                .forUpdate().fetchOne();
        if (row != null) perform(row, "system");
    }

    private Step perform(ActionExecutionsRecord row, String actor) {
        UUID alertId = row.getLiveAlertId();
        int stepNo = row.getStepNo();

        var start = OffsetDateTime.now();
        if (row.getStatus() != ActionStatus.running) row.setStartedAt(start);
        row.setExecutedBy(actor);
        if (row.getApprovalRequired()) {
            row.setApprovedAt(start);
            row.setApprovedBy(actor);
        }
        Map<String, String> params = json.convertValue(parse(row.getParams()), new TypeReference<>() {});
        var template = parse(row.getTemplate());
        var outcome = switch (row.getKind()) {
            case "http" -> http.execute(template, params, row.getId());
            case "escalate" -> escalate(alertId, params.get("team"));
            case "python" -> python.execute(template, scriptContext(alertId, stepNo));
            default -> throw new IllegalStateException("Unsupported stored action kind");
        };
        row.setStatus(outcome.status());
        row.setResult(stored(outcome.result()));
        row.setFinishedAt(OffsetDateTime.now());
        row.update();

        if (row.getAutomatic() != null && outcome.status() != ActionStatus.succeeded) {
            db.update(ACTION_EXECUTIONS).set(ACTION_EXECUTIONS.STATUS, ActionStatus.skipped)
                    .set(ACTION_EXECUTIONS.COMMENT, stopReason(stepNo))
                    .where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)).and(ACTION_EXECUTIONS.STEP_NO.gt(stepNo))
                    .and(ACTION_EXECUTIONS.STATUS.in(ActionStatus.ready, ActionStatus.pending_approval)).execute();
        }
        var audit = json.createObjectNode().put("alertId", alertId.toString()).put("stepNo", stepNo)
                .put("type", row.getAction()).put("confirmed", row.getApprovalRequired()).put("automatic", Boolean.TRUE.equals(row.getAutomatic()))
                .put("status", outcome.status().getLiteral());
        audit.set("result", outcome.result());
        db.insertInto(AUDIT_LOG).set(AUDIT_LOG.ENTITY_TYPE, "action_execution")
                .set(AUDIT_LOG.ENTITY_ID, row.getId().toString()).set(AUDIT_LOG.ACTOR, actor)
                .set(AUDIT_LOG.ACTION, "action.executed").set(AUDIT_LOG.DETAILS, stored(audit)).execute();
        return view(row, true);
    }

    private ObjectNode scriptContext(UUID alertId, int stepNo) {
        var alert = (ObjectNode) parse(db.select(LIVE_ALERT_INBOX.PAYLOAD).from(LIVE_ALERT_INBOX)
                .where(LIVE_ALERT_INBOX.ALERT_ID.eq(alertId)).fetchSingle(LIVE_ALERT_INBOX.PAYLOAD));
        var context = json.createObjectNode();
        context.set("event", alert.remove("event"));
        context.set("alert", alert);
        var previous = db.select(ACTION_EXECUTIONS.KIND, ACTION_EXECUTIONS.RESULT).from(ACTION_EXECUTIONS)
                .where(ACTION_EXECUTIONS.LIVE_ALERT_ID.eq(alertId)).and(ACTION_EXECUTIONS.STEP_NO.eq(stepNo - 1))
                .fetchOne();
        JsonNode value = previous == null ? null : parse(previous.value2());
        if (value != null) {
            if ("http".equals(previous.value1())) value = value.get("response");
            else if ("python".equals(previous.value1())) value = value.get("result");
        }
        context.set("previousStepResult", value);
        return context;
    }

    private HttpAction.Outcome escalate(UUID alertId, String name) {
        var team = db.select(TEAMS.ID).from(TEAMS).where(TEAMS.NAME.eq(name)).fetchOptional(TEAMS.ID);
        if (team.isEmpty()) return new HttpAction.Outcome(ActionStatus.failed,
                json.createObjectNode().put("message", "Unknown team: " + name));
        var alert = db.selectFrom(LIVE_ALERT_INBOX).where(LIVE_ALERT_INBOX.ALERT_ID.eq(alertId)).forUpdate().fetchSingle();
        var payload = (ObjectNode) parse(alert.getPayload());
        payload.putArray("teamIds").add(team.get().toString());
        payload.put("severity", "critical");
        alert.setPayload(stored(payload));
        alert.update();
        return new HttpAction.Outcome(ActionStatus.succeeded, json.createObjectNode().put("team", name).put("severity", "critical"));
    }

    private static String stopReason(int stepNo) { return "Runbook stopped at step " + stepNo; }

    private boolean available(ActionExecutionsRecord row) {
        if (Boolean.TRUE.equals(row.getAutomatic())) return false;
        return switch (row.getStatus()) {
            case ready, pending_approval -> true;
            case failed, succeeded -> row.getAutomatic() == null && "GET".equals(parse(row.getTemplate()).path("method").asText());
            default -> false;
        };
    }

    private Step view(ActionExecutionsRecord row, boolean priorSucceeded) {
        return new Step(row.getStepNo(), row.getAction(), row.getTitle(), parse(row.getParams()), row.getStatus(),
                row.getApprovalRequired(), Boolean.TRUE.equals(row.getAutomatic()),
                (row.getAutomatic() == null || priorSucceeded) && available(row), row.getComment(), parse(row.getResult()),
                row.getKind(), "python".equals(row.getKind()) ? parse(row.getTemplate()).path("code").textValue() : null,
                row.getExecutedBy(), row.getApprovedBy());
    }

    private JsonNode parse(JSONB value) {
        if (value == null) return null;
        try {
            return json.readTree(value.data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored JSON", e);
        }
    }

    private static JSONB stored(JsonNode value) { return JSONB.valueOf(value.toString()); }
}
