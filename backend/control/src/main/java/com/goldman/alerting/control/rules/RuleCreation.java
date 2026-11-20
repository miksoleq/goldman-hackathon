package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.goldman.alerting.control.db.Tables.*;

@Service
class RuleCreation {
    record Created(UUID id, int version, long configVersion) {}
    static class NameConflict extends RuntimeException {}

    private final DSLContext db;
    private final ObjectMapper json;

    RuleCreation(DSLContext db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public Created create(RuleDocument.Draft draft, String source, String actor) {
        var teams = draft.teams().stream().map(this::teamId).toList();
        var users = draft.users().stream().map(username -> db.select(USERS.ID).from(USERS)
                .where(USERS.USERNAME.eq(username)).forKeyShare().fetchOptional(USERS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown user: " + username))).toList();
        UUID runbook = draft.runbook() == null ? null : db.select(RUNBOOKS.ID).from(RUNBOOKS)
                .where(RUNBOOKS.NAME.eq(draft.runbook())).forKeyShare().fetchOptional(RUNBOOKS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown reference: " + draft.runbook()));

        JSONB definition = JSONB.valueOf(draft.definition().toString());
        UUID id = db.insertInto(RULES)
                .set(RULES.NAME, draft.name())
                .set(RULES.ENABLED, draft.enabled())
                .set(RULES.PRIORITY, draft.priority())
                .set(RULES.DEFINITION, definition)
                .set(RULES.SOURCE_YAML, source)
                .set(RULES.RUNBOOK_ID, runbook)
                .set(RULES.UPDATED_BY, actor)
                .onConflict(RULES.NAME).doNothing()
                .returningResult(RULES.ID).fetchOptional(RULES.ID)
                .orElseThrow(NameConflict::new);

        for (UUID team : teams) {
            db.insertInto(RULE_TEAMS, RULE_TEAMS.RULE_ID, RULE_TEAMS.TEAM_ID).values(id, team).execute();
        }
        for (UUID user : users) {
            db.insertInto(RULE_USERS, RULE_USERS.RULE_ID, RULE_USERS.USER_ID)
                    .values(id, user).execute();
        }
        db.insertInto(RULE_VERSIONS)
                .set(RULE_VERSIONS.RULE_ID, id)
                .set(RULE_VERSIONS.VERSION, 1)
                .set(RULE_VERSIONS.DEFINITION, definition)
                .set(RULE_VERSIONS.SOURCE_YAML, source)
                .set(RULE_VERSIONS.CHANGED_BY, actor).execute();

        long revision = db.update(CONFIG_VERSION)
                .set(CONFIG_VERSION.VERSION, CONFIG_VERSION.VERSION.plus(1L))
                .set(CONFIG_VERSION.UPDATED_AT, OffsetDateTime.now())
                .where(CONFIG_VERSION.ID.eq((short) 1))
                .returningResult(CONFIG_VERSION.VERSION).fetchSingle(CONFIG_VERSION.VERSION);
        db.insertInto(AUDIT_LOG)
                .set(AUDIT_LOG.ACTOR, actor)
                .set(AUDIT_LOG.ACTION, "rule.created")
                .set(AUDIT_LOG.ENTITY_TYPE, "rule")
                .set(AUDIT_LOG.ENTITY_ID, id.toString()).execute();

        var payload = json.createObjectNode().put("type", "rule.created").put("ruleId", id.toString())
                .put("version", 1).put("configVersion", revision).put("name", draft.name())
                .put("enabled", draft.enabled()).put("priority", draft.priority());
        payload.set("userIds", json.valueToTree(users));
        payload.set("teamIds", json.valueToTree(teams));
        payload.set("runbookId", json.valueToTree(runbook));
        payload.set("definition", draft.definition());
        db.insertInto(RULE_CREATED_OUTBOX)
                .set(RULE_CREATED_OUTBOX.CONFIG_VERSION, revision)
                .set(RULE_CREATED_OUTBOX.RULE_ID, id)
                .set(RULE_CREATED_OUTBOX.PAYLOAD, JSONB.valueOf(payload.toString())).execute();
        return new Created(id, 1, revision);
    }

    private UUID teamId(String name) {
        return db.select(TEAMS.ID).from(TEAMS).where(TEAMS.NAME.eq(name))
                .forKeyShare().fetchOptional(TEAMS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown reference: " + name));
    }
}
