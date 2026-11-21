package com.goldman.alerting.control.monitor;

import static com.goldman.alerting.control.db.Tables.LIVE_ALERT_INBOX;
import static com.goldman.alerting.control.db.tables.Runbooks.RUNBOOKS;
import static com.goldman.alerting.control.db.tables.Rules.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.LiveAlert;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitorControllerIT {

    private static final String EVENT_STREAM = "events:ingest";

    @Autowired TestRestTemplate http;
    @Autowired DSLContext database;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    private UUID ruleId;
    private UUID runbookId;
    private RecordId messageId;
    private UUID alertId;

    @AfterEach
    void cleanUp() {
        if (messageId != null) redis.opsForStream().delete(EVENT_STREAM, messageId);
        if (alertId != null) {
            database.deleteFrom(LIVE_ALERT_INBOX)
                    .where(LIVE_ALERT_INBOX.ALERT_ID.eq(alertId)).execute();
        }
        if (ruleId != null) database.deleteFrom(RULES).where(RULES.ID.eq(ruleId)).execute();
        if (runbookId != null) database.deleteFrom(RUNBOOKS).where(RUNBOOKS.ID.eq(runbookId)).execute();
    }

    @Test
    void readsRecentMessagesRulesRunbooksAndAlerts() throws Exception {
        runbookId = UUID.randomUUID();
        ruleId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        alertId = UUID.randomUUID();
        String runbookName = "Monitor runbook " + runbookId;
        String ruleName = "Monitor rule " + ruleId;

        database.insertInto(RUNBOOKS)
                .set(RUNBOOKS.ID, runbookId)
                .set(RUNBOOKS.NAME, runbookName)
                .set(RUNBOOKS.FORMAT, "markdown")
                .set(RUNBOOKS.CONTENT, "Check the payment provider.")
                .set(RUNBOOKS.ENABLED, true)
                .execute();
        database.insertInto(RULES)
                .set(RULES.ID, ruleId)
                .set(RULES.NAME, ruleName)
                .set(RULES.ENABLED, true)
                .set(RULES.PRIORITY, 10)
                .set(RULES.DEFINITION, JSONB.valueOf("""
                        {"v":1,"title":"Payment failed","match":{"path":"payload.type","op":"eq","value":"payment_failed"}}
                        """))
                .set(RULES.SOURCE_YAML, "name: " + ruleName + "\n")
                .set(RULES.RUNBOOK_ID, runbookId)
                .set(RULES.VERSION, 1)
                .execute();
        messageId = redis.opsForStream().add(EVENT_STREAM, Map.of("event", """
                {"eventId":"%s","source":"payments","adapter":"http","receivedAt":1795219200000,"contentType":"json","payload":{"type":"payment_failed"},"matchedRuleIds":["%s"]}
                """.formatted(eventId, ruleId)));
        LiveAlert alert = new LiveAlert(1, "alert.created", alertId, eventId, ruleId,
                List.of(), List.of(UUID.randomUUID()), "high", "Payment failed",
                "The payment provider rejected the request.", 1, Instant.parse("2026-11-21T12:00:00Z"),
                runbookId);
        database.insertInto(LIVE_ALERT_INBOX)
                .set(LIVE_ALERT_INBOX.ALERT_ID, alertId)
                .set(LIVE_ALERT_INBOX.PAYLOAD, JSONB.valueOf(mapper.writeValueAsString(alert)))
                .execute();

        JsonNode messages = http.getForObject("/api/monitor/messages", JsonNode.class);
        assertTrue(containsMessage(messages, eventId.toString()), String.valueOf(messages));

        JsonNode rules = http.getForObject("/api/monitor/rules", JsonNode.class);
        assertTrue(containsText(rules, "name", ruleName));
        assertTrue(containsText(rules, "sourceYaml", "name: " + ruleName + "\n"));

        JsonNode runbooks = http.getForObject("/api/monitor/runbooks", JsonNode.class);
        assertTrue(containsText(runbooks, "name", runbookName));
        assertTrue(containsText(runbooks, "content", "Check the payment provider."));

        JsonNode alerts = http.getForObject("/api/monitor/alerts", JsonNode.class);
        assertTrue(containsText(alerts, "alertId", alertId.toString()));
        assertEquals("high", alerts.get(0).path("severity").asText());
    }

    private static boolean containsText(JsonNode array, String field, String expected) {
        for (JsonNode item : array) {
            if (expected.equals(item.path(field).asText())) return true;
        }
        return false;
    }

    private static boolean containsMessage(JsonNode array, String eventId) {
        for (JsonNode item : array) {
            if (eventId.equals(item.path("envelope").path("eventId").asText())) return true;
        }
        return false;
    }
}
