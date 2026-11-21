package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RuleCreated;
import com.goldman.alerting.control.auth.Accounts.UserRef;
import com.goldman.alerting.contracts.Runbook;
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
    static class VersionConflict extends RuntimeException {}
    static class UnknownRule extends RuntimeException {}

    private final DSLContext db;
    private final ObjectMapper json;

    RuleCreation(DSLContext db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public Created create(RuleDocument.Draft draft, String source, UserRef owner) {
        return save(null, 0, draft, source, owner.username(), owner.id());
    }

    @Transactional
    public Created update(UUID id, int expectedVersion, RuleDocument.Draft draft, String source, String actor) {
        return save(id, expectedVersion, draft, source, actor, null);
    }

    private Created save(UUID existingId, int expectedVersion, RuleDocument.Draft draft, String source, String actor, UUID ownerId) {
        var existing = existingId == null ? null : db.selectFrom(RULES).where(RULES.ID.eq(existingId))
                .forUpdate().fetchOptional().orElseThrow(UnknownRule::new);
        if (existing != null && existing.getVersion() != expectedVersion) throw new VersionConflict();
        int version = existing == null ? 1 : existing.getVersion() + 1;
        var teams = draft.teams().stream().map(this::teamId).toList();
        var users = draft.users().stream().map(username -> db.select(USERS.ID).from(USERS)
                .where(USERS.USERNAME.eq(username)).forKeyShare().fetchOptional(USERS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown user: " + username))).toList();
        Runbook book = resolveBook(draft, actor, existing == null ? null : existing.getRunbookId());
        UUID runbook = book == null ? null : book.id();

        JSONB definition = JSONB.valueOf(draft.definition().toString());
        var fields = db.newRecord(RULES);
        fields.setName(draft.name());
        fields.setEnabled(draft.enabled());
        fields.setPriority(draft.priority());
        fields.setDefinition(definition);
        fields.setSourceYaml(source);
        fields.setRunbookId(runbook);
        fields.setUpdatedBy(actor);
        fields.setVersion(version);
        UUID id;
        if (existing == null) {
            fields.setCreatedByUserId(ownerId);
            id = db.insertInto(RULES).set(fields).onConflict(RULES.NAME).doNothing()
                    .returningResult(RULES.ID).fetchOptional(RULES.ID).orElseThrow(NameConflict::new);
        } else {
            id = existingId;
            fields.setUpdatedAt(OffsetDateTime.now());
            try {
                db.update(RULES).set(fields).where(RULES.ID.eq(id)).execute();
            } catch (org.jooq.exception.DataAccessException error) {
                if ("23505".equals(error.sqlState())) throw new NameConflict();
                throw error;
            }
            db.deleteFrom(RULE_TEAMS).where(RULE_TEAMS.RULE_ID.eq(id)).execute();
            db.deleteFrom(RULE_USERS).where(RULE_USERS.RULE_ID.eq(id)).execute();
        }

        for (UUID team : teams) {
            db.insertInto(RULE_TEAMS, RULE_TEAMS.RULE_ID, RULE_TEAMS.TEAM_ID).values(id, team).execute();
        }
        for (UUID user : users) {
            db.insertInto(RULE_USERS, RULE_USERS.RULE_ID, RULE_USERS.USER_ID)
                    .values(id, user).execute();
        }
        db.insertInto(RULE_VERSIONS)
                .set(RULE_VERSIONS.RULE_ID, id)
                .set(RULE_VERSIONS.VERSION, version)
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
                .set(AUDIT_LOG.ACTION, existing == null ? "rule.created" : "rule.updated")
                .set(AUDIT_LOG.ENTITY_TYPE, "rule")
                .set(AUDIT_LOG.ENTITY_ID, id.toString()).execute();

        var payload = new RuleCreated(RuleCreated.TYPE, id.toString(), version, revision, draft.name(),
                draft.enabled(), draft.priority(), teams, users, runbook, draft.definition(), book);
        db.insertInto(RULE_CREATED_OUTBOX)
                .set(RULE_CREATED_OUTBOX.CONFIG_VERSION, revision)
                .set(RULE_CREATED_OUTBOX.RULE_ID, id)
                .set(RULE_CREATED_OUTBOX.PAYLOAD, JSONB.valueOf(json.valueToTree(payload).toString())).execute();
        return new Created(id, version, revision);
    }

    static class RunbookConflict extends RuntimeException {}

    private Runbook resolveBook(RuleDocument.Draft draft, String actor, UUID previousId) {
        if (draft.runbook() == null) return null;
        var inline = draft.inlineBook();
        if (inline != null) {
            if (previousId != null) {
                var unchanged = db.select(RUNBOOKS.ID, RUNBOOKS.NAME, RUNBOOKS.FORMAT, RUNBOOKS.CONTENT).from(RUNBOOKS)
                        .where(RUNBOOKS.ID.eq(previousId).and(RUNBOOKS.ENABLED.isTrue()).and(RUNBOOKS.NAME.eq(draft.runbook()))
                                .and(RUNBOOKS.FORMAT.eq(inline.format())).and(RUNBOOKS.CONTENT.eq(inline.content())))
                        .forShare().fetchOptional(org.jooq.Records.mapping(Runbook::new));
                if (unchanged.isPresent()) return unchanged.get();
            }
            var id = db.insertInto(RUNBOOKS).set(RUNBOOKS.NAME, draft.runbook())
                    .set(RUNBOOKS.FORMAT, inline.format()).set(RUNBOOKS.CONTENT, inline.content())
                    .set(RUNBOOKS.UPDATED_BY, actor).onConflict(RUNBOOKS.NAME).doNothing()
                    .returningResult(RUNBOOKS.ID).fetchOptional(RUNBOOKS.ID).orElseThrow(RunbookConflict::new);
            return new Runbook(id, draft.runbook(), inline.format(), inline.content());
        }
        return db.select(RUNBOOKS.ID, RUNBOOKS.NAME, RUNBOOKS.FORMAT, RUNBOOKS.CONTENT).from(RUNBOOKS)
                .where(RUNBOOKS.NAME.eq(draft.runbook()).and(RUNBOOKS.ENABLED.isTrue()))
                .forShare().fetchOptional(org.jooq.Records.mapping(Runbook::new))
                .orElseThrow(() -> RuleDocument.invalid("Unknown active runbook: " + draft.runbook()));
    }

    private UUID teamId(String name) {
        return db.select(TEAMS.ID).from(TEAMS).where(TEAMS.NAME.eq(name))
                .forKeyShare().fetchOptional(TEAMS.ID)
                .orElseThrow(() -> RuleDocument.invalid("Unknown reference: " + name));
    }
}
