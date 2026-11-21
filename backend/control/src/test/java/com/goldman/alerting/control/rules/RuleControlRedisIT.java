package com.goldman.alerting.control.rules;

import static com.goldman.alerting.control.db.Tables.AUDIT_LOG;
import static com.goldman.alerting.control.db.Tables.RULE_CREATED_OUTBOX;
import static com.goldman.alerting.control.db.Tables.RULES;
import static com.goldman.alerting.control.db.Tables.TEAMS;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "rules.redis.enabled=true",
                "rules.redis.channel=rules.created.it.control",
                "rules.redis.poll-ms=3600000"
        }
)
@DirtiesContext
class RuleControlRedisIT {

    static final String CHANNEL = "rules.created.it.control";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private DSLContext db;

    @Autowired
    private RulePublisher publisher;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private UUID teamId;
    private String bookName;
    private String teamName;
    private String ruleName;

    @BeforeEach
    void createTeamFixture() {
        teamId = UUID.randomUUID();
        teamName = "team-" + teamId;
        ruleName = "rule-" + teamId;
        db.insertInto(TEAMS, TEAMS.ID, TEAMS.NAME).values(teamId, teamName).execute();
    }

    @AfterEach
    void removeDatabaseFixtures() {
        if (ruleName != null) {
            UUID ruleId = db.select(RULES.ID).from(RULES).where(RULES.NAME.eq(ruleName))
                    .fetchOptional(RULES.ID).orElse(null);
            if (ruleId != null) {
                db.deleteFrom(RULE_CREATED_OUTBOX).where(RULE_CREATED_OUTBOX.RULE_ID.eq(ruleId)).execute();
                db.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.ENTITY_ID.eq(ruleId.toString())).execute();
                db.deleteFrom(RULES).where(RULES.ID.eq(ruleId)).execute();
            }
        }
        if (bookName != null) {
            var books = com.goldman.alerting.control.db.Tables.RUNBOOKS;
            db.deleteFrom(books).where(books.NAME.eq(bookName)).execute();
        }
        if (teamId != null) {
            db.deleteFrom(TEAMS).where(TEAMS.ID.eq(teamId)).execute();
        }
    }

    @Test
    void editsYamlWithVersionCheckAndPublishesTheNewSnapshot() throws Exception {
        bookName = "book-" + teamId;
        String original = """
                name: %s
                match: {path: payload, op: exists}
                title: Original
                route: {teams: [%s]}
                runbook: {name: "%s", format: text, content: "Check {{payload.kind}}"}
                """.formatted(ruleName, teamName, bookName);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/yaml"));
        var created = http.postForEntity("/api/rules", new HttpEntity<>(original, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = created.getBody().path("id").textValue();
        String url = "/api/rules/" + id;
        headers.setIfMatch("\"1\"");
        String updated = "# retain this comment\n" + original.replace("title: Original", "title: '{{rule.name}}'");
        var put = org.springframework.http.HttpMethod.PUT;
        assertThat(http.exchange(url, put, new HttpEntity<>(updated.replace("rule.name", "payload | typo"), headers),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var saved = http.exchange(url, put, new HttpEntity<>(updated, headers), JsonNode.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().path("version").asInt()).isEqualTo(2);
        assertThat(http.exchange(url, put, new HttpEntity<>(original, headers), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(db.select(RULES.SOURCE_YAML).from(RULES).where(RULES.ID.eq(UUID.fromString(id)))
                .fetchSingle(RULES.SOURCE_YAML)).isEqualTo(updated);
        publisher.publish();
        try {
            String snapshot = redis.<String, String>opsForHash().get(CHANNEL + ":snapshot", id);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot).path("version").asInt()).isEqualTo(2);
        } finally {
            redis.opsForHash().delete(CHANNEL + ":snapshot", id);
        }
    }

    @Test
    void createsRuleThroughHttpAndPublishesTheOutboxEnvelope() throws Exception {
        bookName = "book-" + teamId;
        String yaml = """
                name: %s
                enabled: true
                priority: 10
                match: {path: payload, op: jsonSchema, value: {type: object, required: [kind], properties: {kind: {type: string}}}}
                title: Payment failed
                route: {teams: [%s]}
                runbook: {name: "%s", format: text, content: "Check {{payload.kind}}"}
                """.formatted(ruleName, teamName, bookName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/yaml"));

        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/rules", new HttpEntity<>(yaml, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        String ruleId = responseBody.path("id").asText();
        assertThat(ruleId).isNotBlank();

        publisher.publish();
        String raw = redis.<String, String>opsForHash().get(CHANNEL + ":snapshot", ruleId);
        JsonNode event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        redis.opsForHash().delete(CHANNEL + ":snapshot", ruleId);
        assertThat(event.at("/runbook/content").asText()).isEqualTo("Check {{payload.kind}}");
        assertThat(event.at("/runbook/id")).isEqualTo(event.path("runbookId"));
        var conflict = http.postForEntity("/api/rules",
                new HttpEntity<>(yaml.replace(bookName, "orphan-" + teamId), headers), JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var books = com.goldman.alerting.control.db.Tables.RUNBOOKS;
        assertThat(db.fetchExists(books, books.NAME.eq("orphan-" + teamId))).isFalse();
        assertThat(event.path("type").asText()).isEqualTo("rule.created");
        assertThat(event.path("ruleId").asText()).isEqualTo(ruleId);
        assertThat(event.path("enabled").asBoolean()).isTrue();
        assertThat(event.path("priority").asInt()).isEqualTo(10);
        assertThat(event.path("definition").path("v").asInt()).isEqualTo(1);
        assertThat(event.path("definition").at("/match/value/properties/kind/type").asText()).isEqualTo("string");
        assertThat(event.path("teamIds").get(0).asText()).isEqualTo(teamId.toString());
    }
}
