package com.goldman.alerting.engine.ingest.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.EngineApplication;
import com.goldman.alerting.engine.rules.RuleIndex;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.time.Duration;
import com.goldman.alerting.contracts.LiveAlert;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.test.annotation.DirtiesContext;
import org.awaitility.Awaitility;

@SpringBootTest(
        classes = EngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rules.redis.enabled=true",
                "rules.redis.channel=rules.created.it",
                "ingest.redis.enabled=false"
        }
)
@DirtiesContext
class RedisRuleCreatedAdapterIT {

    static final String CHANNEL = "rules.created.it";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RuleIndexManager manager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test void preservesExistingExternalConsumerGroupOnRestart() {
        String external = "test:external:" + UUID.randomUUID();
        try {
            var adapter = new RedisExternalStreamAdapter(redis, ingestion, external, "engine", "first", 1);
            adapter.run(null);
            adapter.run(null);
            assertEquals(1, redis.opsForStream().groups(external).size());
        } finally {
            redis.delete(external);
        }
    }

    private static final String stream = "it:events:" + UUID.randomUUID();
    @org.springframework.test.context.DynamicPropertySource
    static void streamProperty(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("ingest.redis.internal-stream", () -> stream);
        registry.add("alerts.stream", () -> stream + ":alerts");
    }

    @Autowired
    private com.goldman.alerting.engine.ingest.IngestionService ingestion;

    @Test
    void redisRuleCreationBuildsBucketsAndHandlesDuplicateOutboxDelivery() throws Exception {
        String id = UUID.randomUUID().toString();
        JsonNode paymentFailed = json("{\"payload\":{\"kind\":\"payment_failed\"}}");
        JsonNode customerLocked = json("{\"payload\":{\"kind\":\"customer_locked\"}}");
        RuleIndex emptySnapshot = manager.snapshot();

        String message = message(id, "payment_failed");
        redis.convertAndSend(CHANNEL, message);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(List.of(id), ids(manager.match(paymentFailed))));

        var alerts = redis.<String, String>opsForStream();
        String alertStream = stream + ":alerts";
        try {
            var accepted = ingestion.accept("it", "http", paymentFailed.get("payload"));
            var messages = alerts.range(alertStream, org.springframework.data.domain.Range.unbounded());
            assertEquals(1, messages.size());
            var wire = objectMapper.readTree(messages.get(0).getValue().get("alert"));
            assertEquals("alert.created", wire.path("type").asText());
            assertEquals(1, wire.path("v").intValue());
            assertEquals(1, wire.path("count").intValue());
            assertEquals(accepted.eventId(), wire.path("eventId").asText());
            assertEquals(accepted.receivedAt(), com.github.f4b6a3.ulid.Ulid.from(accepted.eventId()).getTime());
            var storedEvent = alerts.range(stream, org.springframework.data.domain.Range.unbounded()).getFirst();
            assertEquals(accepted.eventId(), objectMapper.readTree(storedEvent.getValue().get("event")).required("eventId").asText());
            assertEquals(id, wire.path("ruleId").asText());
            assertEquals("54989321-a1a2-4370-bc28-634ebd96ec50", wire.at("/userIds/0").asText());
            assertTrue(wire.path("teamIds").isEmpty());
            assertEquals(LiveAlert.UNASSIGNED_RUNBOOK.toString(), wire.path("runbookId").asText());
            java.time.Instant.parse(wire.path("occurredAt").asText());
            UUID.fromString(wire.path("alertId").asText());
            assertEquals(List.of(id), accepted.matchedRuleIds());
            var otherShape = ingestion.accept("it", "http", "{\"kind\":\"payment_failed\",\"extra\":1}");
            assertTrue(otherShape.matchedRuleIds().isEmpty());
            assertEquals(1L, alerts.size(alertStream));
        } finally {
            redis.delete(List.of(stream, alertStream));
        }

        RuleIndex createdSnapshot = manager.snapshot();
        assertEquals(0, emptySnapshot.ruleCount());
        assertTrue(createdSnapshot.match(customerLocked).isEmpty());

        redis.convertAndSend(CHANNEL, message);
        String nextId = UUID.randomUUID().toString();
        redis.convertAndSend(CHANNEL, message(nextId, "customer_locked"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> manager.snapshot().ruleCount() == 2);
        assertEquals(List.of(id), ids(createdSnapshot.match(paymentFailed)));
        assertTrue(createdSnapshot.match(customerLocked).isEmpty());
        assertEquals(2, manager.snapshot().ruleCount());
    }

    @Test
    void bothLiveReplicasIndexTheSamePublication() throws Exception {
        String channel = CHANNEL + ".replicas";
        var factory = redis.getRequiredConnectionFactory();
        var first = new RuleIndexManager(List.of());
        var second = new RuleIndexManager(List.of());
        var config = new RuleSubscription();
        var listeners = List.of(
                config.ruleSubscriptionContainer(factory, new RedisRuleCreatedAdapter(objectMapper, first, redis, channel), channel),
                config.ruleSubscriptionContainer(factory, new RedisRuleCreatedAdapter(objectMapper, second, redis, channel), channel));
        try {
            for (var listener : listeners) {
                listener.afterPropertiesSet();
                listener.start();
            }
            String id = UUID.randomUUID().toString();
            var envelope = json("{\"payload\":{\"kind\":\"broadcast\"}}");
            assertEquals(2L, redis.convertAndSend(channel, message(id, "broadcast")));
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(List.of(id), ids(first.match(envelope)));
                assertEquals(List.of(id), ids(second.match(envelope)));
            });
        } finally {
            for (var listener : listeners) listener.destroy();
        }
    }

    @Test
    void disconnectedReplicaRestoresRulesWithoutANewPublication() throws Exception {
        var replica = new RuleIndexManager(List.of());
        var adapter = new RedisRuleCreatedAdapter(objectMapper, replica, redis, CHANNEL + ".recovery");
        String id = UUID.randomUUID().toString();
        redis.opsForHash().put(CHANNEL + ".recovery:snapshot", id, message(id, "offline"));
        try {
            adapter.recover();
            assertEquals(List.of(id), ids(replica.match(json("{\"payload\":{\"kind\":\"offline\"}}"))));
        } finally {
            redis.opsForHash().delete(CHANNEL + ".recovery:snapshot", id);
        }
    }

    private String message(String id, String kind) {
        return """
                {"type":"rule.created","ruleId":"%s","version":1,"configVersion":12,"name":"Created from Control","enabled":true,"priority":100,"teamIds":[],"userIds":["54989321-a1a2-4370-bc28-634ebd96ec50"],"runbookId":null,"definition":{"v":1,"title":"Created from Control","match":{"all":[{"path":"payload.kind","op":"eq","value":"%s"},{"path":"payload","op":"jsonSchema","value":{"type":"object","required":["kind"],"properties":{"kind":{"type":"string"}},"additionalProperties":false}}]}}}
                """.formatted(id, kind);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private List<String> ids(List<com.goldman.alerting.engine.rules.Rule> rules) {
        return rules.stream().map(rule -> rule.id()).toList();
    }
}
