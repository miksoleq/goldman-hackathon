package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RuleCreated;
import com.goldman.alerting.contracts.Runbook;
import com.goldman.alerting.control.actions.ActionCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import com.goldman.alerting.control.auth.Accounts.UserRef;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.goldman.alerting.control.db.Tables.*;

@Service
class RuleCreation {
    record Created(UUID id, int version, long configVersion, UserRef createdBy) {}
    static class NameConflict extends RuntimeException {}

    private final DSLContext db;
    private final ObjectMapper json;

    private final ActionCatalog catalog;

    RuleCreation(DSLContext db, ObjectMapper json, ActionCatalog catalog) {
        this.db = db;
        this.json = json;
        this.catalog = catalog;
    }

    @Transactional
    public Created create(RuleDocument.Draft draft, String source, UserRef owner) {
        String actor = owner.username();
        var teams = draft.teams().stream().map(this::teamId).toList();
        var users = draft.users().stream().map(username -> db.select(USERS.ID).from(USERS)
                .where(USERS.USERNAME.eq(username)).forKeyShare().fetchOptional(USERS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown user: " + username))).toList();
        Runbook book = resolveBook(draft, actor);
        UUID runbook = book == null ? null : book.id();

        JSONB definition = JSONB.valueOf(draft.definition().toString());
        UUID id = db.insertInto(RULES)
                .set(RULES.NAME, draft.name())
                .set(RULES.ENABLED, draft.enabled())
                .set(RULES.PRIORITY, draft.priority())
                .set(RULES.DEFINITION, definition)
                .set(RULES.SOURCE_YAML, source)
                .set(RULES.RUNBOOK_ID, runbook)
                .set(RULES.CREATED_BY_USER_ID, owner.id())
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

        var payload = new RuleCreated(RuleCreated.TYPE, id.toString(), 1, revision, draft.name(),
                draft.enabled(), draft.priority(), teams, users, runbook, draft.definition(), book);
        db.insertInto(RULE_CREATED_OUTBOX)
                .set(RULE_CREATED_OUTBOX.CONFIG_VERSION, revision)
                .set(RULE_CREATED_OUTBOX.RULE_ID, id)
                .set(RULE_CREATED_OUTBOX.PAYLOAD, JSONB.valueOf(json.valueToTree(payload).toString())).execute();
        return new Created(id, 1, revision, owner);
    }

    static class RunbookConflict extends RuntimeException {}

    private Runbook resolveBook(RuleDocument.Draft draft, String actor) {
        if (draft.runbook() == null) return null;
        var inline = draft.inlineBook();
        if (inline != null) {
            return createBook(draft.runbook(), inline, actor);
        }
        var row = db.selectFrom(RUNBOOKS)
                .where(RUNBOOKS.NAME.eq(draft.runbook()).and(RUNBOOKS.ENABLED.isTrue()))
                .forShare().fetchOptional()
                .orElseThrow(() -> RuleDocument.invalid("Unknown active runbook: " + draft.runbook()));
        try {
            List<Runbook.Action> actions = json.readValue(row.getActions().data(), new TypeReference<>() {});
            actions.forEach(catalog::resolve);
            return new Runbook(row.getId(), row.getName(), row.getFormat(), row.getContent(), actions);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored runbook", e);
        }
    }

    @Transactional
    public Runbook createBook(String name, RuleDocument.InlineBook inline, String actor) {
        inline.actions().forEach(catalog::resolve);
        var id = db.insertInto(RUNBOOKS).set(RUNBOOKS.NAME, name)
                .set(RUNBOOKS.FORMAT, inline.format()).set(RUNBOOKS.CONTENT, inline.content())
                .set(RUNBOOKS.ACTIONS, JSONB.valueOf(json.valueToTree(inline.actions()).toString()))
                .set(RUNBOOKS.UPDATED_BY, actor).onConflict(RUNBOOKS.NAME).doNothing()
                .returningResult(RUNBOOKS.ID).fetchOptional(RUNBOOKS.ID).orElseThrow(RunbookConflict::new);
        return new Runbook(id, name, inline.format(), inline.content(), inline.actions());
    }

    private UUID teamId(String name) {
        return db.select(TEAMS.ID).from(TEAMS).where(TEAMS.NAME.eq(name))
                .forKeyShare().fetchOptional(TEAMS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown reference: " + name));
    }
}
