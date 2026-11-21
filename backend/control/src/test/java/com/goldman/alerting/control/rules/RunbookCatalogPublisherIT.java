package com.goldman.alerting.control.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RunbookCandidate;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com.goldman.alerting.control.db.Tables.RUNBOOKS;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(properties = {"rules.redis.enabled=true", "runbooks.catalog.poll-ms=100"})
class RunbookCatalogPublisherIT {
    private static final String CHANNEL = "it:catalog:" + UUID.randomUUID();
    @DynamicPropertySource static void channel(DynamicPropertyRegistry properties) {
        properties.add("rules.redis.channel", () -> CHANNEL);
    }
    @Autowired DSLContext db;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    @Test void publishesEditsAndWithdrawsDisabledRunbooks() {
        var id = UUID.randomUUID();
        try {
            db.insertInto(RUNBOOKS).set(RUNBOOKS.ID, id).set(RUNBOOKS.NAME, "it-" + id)
                    .set(RUNBOOKS.FORMAT, "text").set(RUNBOOKS.CONTENT, "original")
                    .set(RUNBOOKS.MATCHES, JSONB.valueOf("{\"path\":\"payload\",\"op\":\"exists\"}")).execute();
            awaitContent(id, "original");
            db.update(RUNBOOKS).set(RUNBOOKS.CONTENT, "replacement").where(RUNBOOKS.ID.eq(id)).execute();
            awaitContent(id, "replacement");
            db.update(RUNBOOKS).set(RUNBOOKS.ENABLED, false).where(RUNBOOKS.ID.eq(id)).execute();
            awaitContent(id, null);
        } finally {
            db.deleteFrom(RUNBOOKS).where(RUNBOOKS.ID.eq(id)).execute();
            redis.delete(RunbookCandidate.snapshotKey(CHANNEL));
        }
    }

    private void awaitContent(UUID id, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var raw = redis.opsForValue().get(RunbookCandidate.snapshotKey(CHANNEL));
            assertNotNull(raw);
            String content = null;
            for (JsonNode candidate : mapper.readTree(raw)) {
                if (id.toString().equals(candidate.at("/runbook/id").asText())) {
                    content = candidate.at("/runbook/content").asText();
                    assertEquals("exists", candidate.at("/matches/op").asText());
                }
            }
            assertEquals(expected, content);
        });
    }
}
