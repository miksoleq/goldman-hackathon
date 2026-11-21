package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.*;
import static com.goldman.alerting.control.db.Tables.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "rules.redis.enabled=false")
class RunbookEditorIT {
    @Autowired TestRestTemplate http;
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, String cookie, String etag) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
        if (etag != null) headers.set(HttpHeaders.IF_MATCH, etag);
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test void legacyUrlAndInlineActionSurviveOpeningAndSavingAsText() {
        var cookie = call(POST, "/auth/login", Map.of("username","demo.leader","password","Runbook123!"), null, null)
                .getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        var name = "legacy-" + UUID.randomUUID();
        var link = "https://example.test/instructions";
        var steps = json.createArrayNode();
        steps.addObject().put("type", "python").put("approval", "required").put("timeoutMs", 1000)
                .put("code", "def run(ctx): return 1").putObject("params");
        var id = db.insertInto(RUNBOOKS).set(RUNBOOKS.NAME, name).set(RUNBOOKS.FORMAT, "url")
                .set(RUNBOOKS.CONTENT, link).set(RUNBOOKS.ACTIONS, org.jooq.JSONB.valueOf(steps.toString()))
                .returningResult(RUNBOOKS.ID).fetchSingle(RUNBOOKS.ID);
        try {
            var path = "/runbooks/" + id;
            var legacy = call(GET, path, null, cookie, null);
            assertThat(legacy.getBody().path("format").asText()).isEqualTo("url");
            var document = Map.of("kind", "Runbook", "name", name,
                    "spec", Map.of("format", "text", "content", link, "actions", legacy.getBody().get("actions")));
            var saved = call(PUT, path, document, cookie, legacy.getHeaders().getETag());
            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(saved.getBody().path("content").asText()).isEqualTo(link);
            assertThat(saved.getBody().get("actions")).isEqualTo(steps);
        } finally {
            db.deleteFrom(RUNBOOKS).where(RUNBOOKS.ID.eq(id)).execute();
            db.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(id.toString())).execute();
        }
    }

    @Test void editsByIdAndPublishesNewSnapshotsForEveryAttachedRule() throws Exception {
        String name = "edit-" + UUID.randomUUID();
        var cookie = call(POST, "/auth/login", Map.of("username","demo.member","password","Runbook123!"), null, null)
                .getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        var doc = json.createObjectNode().put("kind", "Runbook").put("name", name);
        var spec = doc.putObject("spec").put("format", "text").put("content", "First");
        assertThat(call(GET, "/action-types", null, cookie, null).getBody().toString()).contains("escalate", "workflowId");
        var created = call(POST, "/apply", doc, cookie, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(created.getBody().path("id").asText());
        String path = "/runbooks/" + id;
        List<UUID> rules = new ArrayList<>();
        try {
            for (int i = 0; i < 2; ++i) {
                var rule = Map.of("name", name + i, "title", "Alert", "route", Map.of("users", List.of("demo.member")),
                        "match", Map.of("path","payload.id","op","exists"), "runbook", name);
                rules.add(UUID.fromString(call(POST, "/rules", rule, cookie, null).getBody().path("id").asText()));
            }
            var tag = call(GET, path, null, cookie, null).getHeaders().getETag();
            assertThat(call(PUT, path, doc, null, tag).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(call(PUT, path, doc, cookie, null).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
            doc.put("name", "script-runner-diagnostics");
            assertThat(call(PUT, path, doc, cookie, tag).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            doc.put("name", name + "-edited");
            spec.put("format","yaml").put("content","steps: [");
            assertThat(call(PUT, path, doc, cookie, tag).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            String content = "steps:\n  - Check\n";
            spec.put("content", content).putArray("actions").addObject().put("type","escalate")
                    .putObject("params").put("team","demo-operations");
            var saved = call(PUT, path, doc, cookie, tag);
            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(saved.getBody().path("id").asText()).isEqualTo(id.toString());
            assertThat(saved.getHeaders().getETag()).isNotEqualTo(tag);
            assertThat(call(PUT, path, doc, cookie, tag).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
            for (var rule : rules) {
                var snapshots = db.select(RULE_CREATED_OUTBOX.PAYLOAD).from(RULE_CREATED_OUTBOX)
                        .where(RULE_CREATED_OUTBOX.RULE_ID.eq(rule)).orderBy(RULE_CREATED_OUTBOX.CONFIG_VERSION)
                        .fetch(RULE_CREATED_OUTBOX.PAYLOAD);
                assertThat(snapshots).hasSize(2);
                var original = json.readTree(snapshots.getFirst().data());
                var updated = json.readTree(snapshots.getLast().data());
                assertThat(original.at("/runbook/content").asText()).isEqualTo("First");
                assertThat(updated.at("/runbook/content").asText()).isEqualTo(content);
                assertThat(updated.at("/runbook/actions/0/type").asText()).isEqualTo("escalate");
                assertThat(updated.path("userIds")).isEqualTo(original.path("userIds"));
                assertThat(updated.path("version").asInt()).isEqualTo(2);
            }
            spec.put("format", "markdown").put("content", "**Review**");
            assertThat(call(PUT, path, doc, cookie, saved.getHeaders().getETag()).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(GET, path, null, cookie, null).getBody().path("content").asText()).isEqualTo("**Review**");
        } finally {
            db.deleteFrom(RULE_CREATED_OUTBOX).where(RULE_CREATED_OUTBOX.RULE_ID.in(rules)).execute();
            db.deleteFrom(RULES).where(RULES.ID.in(rules)).execute();
            db.deleteFrom(RUNBOOKS).where(RUNBOOKS.ID.eq(id)).execute();
            var entities = new ArrayList<>(rules.stream().map(UUID::toString).toList());
            entities.add(id.toString());
            db.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.in(entities)).execute();
        }
    }
}
