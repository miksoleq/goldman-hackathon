package com.goldman.alerting.engine.ingest.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.EngineApplication;
import com.goldman.alerting.engine.rules.RuleIndex;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.goldman.alerting.schema.LiveAlert;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.awaitility.Awaitility;

@SpringBootTest(
        classes = EngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rules.kafka.enabled=true",
                "rules.kafka.consumer-group=engine-rules-integration",
                "ingest.redis.enabled=false",
                "spring.kafka.consumer.auto-offset-reset=earliest"
        }
)
@EmbeddedKafka(
        partitions = 1,
        topics = KafkaRuleCreatedAdapterIT.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DirtiesContext
class KafkaRuleCreatedAdapterIT {

    static final String TOPIC = "rules.created.v1";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RuleIndexManager manager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired private StringRedisTemplate redis;
    private static final String stream = "it:events:" + UUID.randomUUID();
    @org.springframework.test.context.DynamicPropertySource
    static void streamProperty(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("ingest.redis.internal-stream", () -> stream);
    }

    @Autowired
    private com.goldman.alerting.engine.ingest.IngestionService ingestion;

    @Test
    void kafkaRuleCreationBuildsBucketsAndHandlesDuplicateOutboxDelivery() throws Exception {
        String id = UUID.randomUUID().toString();
        JsonNode paymentFailed = json("{\"payload\":{\"kind\":\"payment_failed\"}}");
        JsonNode customerLocked = json("{\"payload\":{\"kind\":\"customer_locked\"}}");
        RuleIndex emptySnapshot = manager.snapshot();

        String message = message(id, "payment_failed");
        publish(id, message);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(List.of(id), ids(manager.match(paymentFailed))));

        var messages = new LinkedBlockingQueue<byte[]>();
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redis.getConnectionFactory());
        listener.addMessageListener((msg, pattern) -> messages.add(msg.getBody()), new ChannelTopic(LiveAlert.CHANNEL));
        listener.afterPropertiesSet();
        try {
            listener.start();
            var accepted = ingestion.accept("it", "http", paymentFailed.get("payload"));
            var messageBytes = messages.poll(10, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(messageBytes);
            var wire = objectMapper.readTree(messageBytes);
            assertEquals("alert.created", wire.path("type").asText());
            assertEquals(1, wire.path("v").intValue());
            assertEquals(1, wire.path("count").intValue());
            assertEquals(accepted.eventId(), wire.path("eventId").asText());
            assertEquals(id, wire.path("ruleId").asText());
            assertEquals("54989321-a1a2-4370-bc28-634ebd96ec50", wire.at("/userIds/0").asText());
            assertTrue(wire.path("teamIds").isEmpty());
            assertEquals(LiveAlert.UNASSIGNED_RUNBOOK.toString(), wire.path("runbookId").asText());
            java.time.Instant.parse(wire.path("occurredAt").asText());
            UUID.fromString(wire.path("alertId").asText());
            assertEquals(List.of(id), accepted.matchedRuleIds());
            var otherShape = ingestion.accept("it", "http", "{\"kind\":\"payment_failed\",\"extra\":1}");
            assertTrue(otherShape.matchedRuleIds().isEmpty());
            org.junit.jupiter.api.Assertions.assertNull(messages.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            listener.destroy();
            redis.delete(stream);
        }

        RuleIndex createdSnapshot = manager.snapshot();
        assertEquals(0, emptySnapshot.ruleCount());
        assertTrue(createdSnapshot.match(customerLocked).isEmpty());

        publish(id, message);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> manager.snapshot() != createdSnapshot);
        assertEquals(1, manager.snapshot().ruleCount());
        assertEquals(List.of(id), ids(createdSnapshot.match(paymentFailed)));
        assertTrue(createdSnapshot.match(customerLocked).isEmpty());
        assertEquals(1, manager.snapshot().ruleCount());
    }

    private void publish(String id, String message) throws Exception {
        kafkaTemplate.send(TOPIC, id, message).get(10, TimeUnit.SECONDS);
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
