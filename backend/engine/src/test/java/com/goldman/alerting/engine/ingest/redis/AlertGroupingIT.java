package com.goldman.alerting.engine.ingest.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RuleCreated;
import com.goldman.alerting.engine.ingest.IngestionService;
import com.goldman.alerting.engine.rules.RuleDefinition;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ingest.redis.enabled=false")
class AlertGroupingIT {
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;

    @Test void replicasShareFixedWindowsAndPublishOnlyTheOrderedMatches() throws Exception {
        String prefix = "it:grouping:" + UUID.randomUUID();
        String events = prefix + ":events";
        String alerts = prefix + ":alerts";
        var early = rule("A", 10, false);
        var stop = rule("B", 10, true);
        var later = rule("A", 20, false);
        var rules = List.of(later, stop, early);
        var sender = new RedisStreamPublisher(redis, json, events, alerts);
        var first = new IngestionService(json, sender, new RuleIndexManager(rules), 4096);
        var second = new IngestionService(json, sender, new RuleIndexManager(rules), 4096);
        var payload = json.readTree("{\"amount\":1,\"attempt\":1}");
        var event = json.createObjectNode().set("payload", payload);
        var keys = new ArrayList<>(List.of(events, alerts));
        for (var rule : rules) keys.add(alerts + ":dedup:" + rule.dedup().fingerprint(rule.id(), event));
        var stream = redis.<String,String>opsForStream();
        try {
            var accepted = first.accept("demo", "http", payload);
            assertEquals(List.of(early.id(), stop.id()), accepted.matchedRuleIds());
            var original = stream.range(alerts, Range.unbounded());
            assertEquals(2, original.size());
            var a = json.readTree(original.get(0).getValue().get("alert"));
            var b = json.readTree(original.get(1).getValue().get("alert"));
            assertEquals(early.id(), a.path("ruleId").asText());
            assertEquals(stop.id(), b.path("ruleId").asText());
            assertNotEquals(a.get("alertId"), b.get("alertId"));

            String key = alerts + ":dedup:" + early.dedup().fingerprint(early.id(), event);
            assertTrue(redis.getExpire(key) > 0 && redis.getExpire(key) <= 60);
            redis.expire(key, Duration.ofSeconds(20));
            var tasks = new ArrayList<CompletableFuture<?>>();
            for (var replica : List.of(first, second, first, second)) {
                tasks.add(CompletableFuture.runAsync(() ->
                        replica.accept("demo", "http", "{\"amount\":1.00,\"attempt\":2}")));
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            assertTrue(redis.getExpire(key) <= 20);
            var records = stream.range(alerts, Range.unbounded());
            assertEquals(10, records.size());
            for (int i = 2; i < records.size(); i++) {
                JsonNode update = json.readTree(records.get(i).getValue().get("alert"));
                assertEquals("alert.counted", update.path("type").asText());
                assertEquals(i % 2 == 0 ? a.get("alertId") : b.get("alertId"), update.get("alertId"));
                assertEquals(i / 2 + 1, update.path("count").asInt());
            }
            redis.expire(key, Duration.ofMillis(20));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !redis.hasKey(key));
            second.accept("demo", "http", payload);
            var last = stream.range(alerts, Range.unbounded());
            var fresh = json.readTree(last.get(10).getValue().get("alert"));
            assertEquals("alert.created", fresh.path("type").asText());
            assertNotEquals(a.get("alertId"), fresh.get("alertId"));
            assertEquals(1, fresh.path("count").asInt());
            assertEquals(6L, stream.size(events));
        } finally {
            redis.delete(keys);
        }
    }

    private RuleDefinition rule(String name, int priority, boolean stop) throws Exception {
        String id = UUID.randomUUID().toString();
        var definition = json.readTree("""
                {"v":1,"match":{"path":"payload.amount","op":"exists"},"title":"{{rule.name}}",
                 "stopProcessing":%s,"dedup":{"keys":["payload.amount"],"windowSec":60}}
                """.formatted(stop));
        return RuleDefinition.fromEvent(new RuleCreated(RuleCreated.TYPE, id, 1, 1L, name, true, priority,
                List.of(), List.of(), null, definition, null));
    }
}
