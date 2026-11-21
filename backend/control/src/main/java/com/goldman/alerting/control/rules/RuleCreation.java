package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RuleCreated;
import com.goldman.alerting.contracts.Runbook;
import com.goldman.alerting.control.actions.ActionCatalog;
import com.goldman.alerting.control.auth.Accounts.UserRef;
import com.goldman.alerting.control.db.tables.records.RulesRecord;
import com.goldman.alerting.control.db.tables.records.RunbooksRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.goldman.alerting.control.db.Tables.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PRECONDITION_FAILED;

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
        var rule = db.insertInto(RULES)
                .set(RULES.NAME, draft.name())
                .set(RULES.ENABLED, draft.enabled())
                .set(RULES.PRIORITY, draft.priority())
                .set(RULES.DEFINITION, definition)
                .set(RULES.SOURCE_YAML, source)
                .set(RULES.RUNBOOK_ID, runbook)
                .set(RULES.CREATED_BY_USER_ID, owner.id())
                .set(RULES.UPDATED_BY, actor)
                .onConflict(RULES.NAME).doNothing()
                .returning().fetchOptional()
                .orElseThrow(NameConflict::new);

        UUID id = rule.getId();
        for (UUID team : teams) {
            db.insertInto(RULE_TEAMS, RULE_TEAMS.RULE_ID, RULE_TEAMS.TEAM_ID).values(id, team).execute();
        }
        for (UUID user : users) {
            db.insertInto(RULE_USERS, RULE_USERS.RULE_ID, RULE_USERS.USER_ID)
                    .values(id, user).execute();
        }
        long revision = publish(rule, teams, users, actor, book);
        audit("rule", id, "rule.created", actor);
        return new Created(id, 1, revision, owner);
    }

    private long publish(RulesRecord rule, List<UUID> teams, List<UUID> users, String actor, Runbook book) {
        JsonNode definition;
        try {
            definition = json.readTree(rule.getDefinition().data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored rule", e);
        }
        UUID id = rule.getId();
        db.insertInto(RULE_VERSIONS)
                .set(RULE_VERSIONS.RULE_ID, id).set(RULE_VERSIONS.VERSION, rule.getVersion())
                .set(RULE_VERSIONS.DEFINITION, rule.getDefinition())
                .set(RULE_VERSIONS.SOURCE_YAML, rule.getSourceYaml()).set(RULE_VERSIONS.CHANGED_BY, actor).execute();
        long revision = db.update(CONFIG_VERSION)
                .set(CONFIG_VERSION.VERSION, CONFIG_VERSION.VERSION.plus(1L))
                .set(CONFIG_VERSION.UPDATED_AT, OffsetDateTime.now())
                .where(CONFIG_VERSION.ID.eq((short) 1))
                .returningResult(CONFIG_VERSION.VERSION).fetchSingle(CONFIG_VERSION.VERSION);
        var payload = new RuleCreated(RuleCreated.TYPE, id.toString(), rule.getVersion(), revision, rule.getName(),
                rule.getEnabled(), rule.getPriority(), teams, users, rule.getRunbookId(), definition, book);
        db.insertInto(RULE_CREATED_OUTBOX)
                .set(RULE_CREATED_OUTBOX.CONFIG_VERSION, revision).set(RULE_CREATED_OUTBOX.RULE_ID, id)
                .set(RULE_CREATED_OUTBOX.PAYLOAD, JSONB.valueOf(json.valueToTree(payload).toString())).execute();
        return revision;
    }

    private void audit(String entity, UUID id, String action, String actor) {
        db.insertInto(AUDIT_LOG).set(AUDIT_LOG.ACTOR, actor).set(AUDIT_LOG.ACTION, action)
                .set(AUDIT_LOG.ENTITY_TYPE, entity).set(AUDIT_LOG.ENTITY_ID, id.toString()).execute();
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
        var book = storedBook(row);
        book.actions().forEach(catalog::resolve);
        return book;
    }

    private Runbook storedBook(RunbooksRecord row) {
        try {
            List<Runbook.Action> actions = json.readValue(row.getActions().data(), new TypeReference<>() {});
            return new Runbook(row.getId(), row.getName(), row.getFormat(), row.getContent(), actions);
        } catch (JsonProcessingException e) {
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
        audit("runbook", id, "runbook.created", actor);
        return new Runbook(id, name, inline.format(), inline.content(), inline.actions());
    }

    record BookEdition(Runbook book, String etag) {}

    private BookEdition edition(RunbooksRecord row) {
        return new BookEdition(storedBook(row), etag(row));
    }

    private static String etag(RunbooksRecord row) {
        return "\"" + row.getUpdatedAt().toInstant() + "\"";
    }

    BookEdition readBook(UUID id) {
        return edition(db.selectFrom(RUNBOOKS).where(RUNBOOKS.ID.eq(id)).fetchOptional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND)));
    }

    @Transactional
    public BookEdition updateBook(UUID id, String expected, String name, RuleDocument.InlineBook inline, String actor) {
        var row = db.selectFrom(RUNBOOKS).where(RUNBOOKS.ID.eq(id)).forUpdate().fetchOptional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if (!etag(row).equals(expected)) throw new ResponseStatusException(PRECONDITION_FAILED);
        inline.actions().forEach(catalog::resolve);
        row.setName(name);
        row.setFormat(inline.format());
        row.setContent(inline.content());
        row.setActions(JSONB.valueOf(json.valueToTree(inline.actions()).toString()));
        row.setUpdatedBy(actor);
        var earliest = row.getUpdatedAt().plusNanos(1000);
        var now = OffsetDateTime.now();
        row.setUpdatedAt(now.isAfter(earliest) ? now : earliest);
        try {
            row.update();
        } catch (DuplicateKeyException e) {
            throw new RunbookConflict();
        }
        row.refresh();
        var updated = edition(row);
        for (var rule : db.selectFrom(RULES).where(RULES.RUNBOOK_ID.eq(id)).orderBy(RULES.ID).forUpdate().fetch()) {
            rule.setVersion(rule.getVersion() + 1);
            rule.setUpdatedBy(actor);
            rule.setUpdatedAt(row.getUpdatedAt());
            rule.update();
            var teams = db.select(RULE_TEAMS.TEAM_ID).from(RULE_TEAMS).where(RULE_TEAMS.RULE_ID.eq(rule.getId()))
                    .orderBy(RULE_TEAMS.TEAM_ID).fetch(RULE_TEAMS.TEAM_ID);
            var users = db.select(RULE_USERS.USER_ID).from(RULE_USERS).where(RULE_USERS.RULE_ID.eq(rule.getId()))
                    .orderBy(RULE_USERS.USER_ID).fetch(RULE_USERS.USER_ID);
            publish(rule, teams, users, actor, updated.book());
        }
        audit("runbook", id, "runbook.updated", actor);
        return updated;
    }

    private UUID teamId(String name) {
        return db.select(TEAMS.ID).from(TEAMS).where(TEAMS.NAME.eq(name))
                .forKeyShare().fetchOptional(TEAMS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown reference: " + name));
    }
}
