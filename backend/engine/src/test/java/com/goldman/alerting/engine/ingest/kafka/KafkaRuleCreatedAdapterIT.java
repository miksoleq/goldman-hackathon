package com.goldman.alerting.engine.ingest.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.EngineApplication;
import com.goldman.alerting.engine.rules.RuleIndex;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.time.Duration;
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

    @Test
    void kafkaRuleCreationBuildsBucketsAndHandlesDuplicateOutboxDelivery() throws Exception {
        String id = "rule-" + UUID.randomUUID();
        JsonNode paymentFailed = json("{\"payload\":{\"kind\":\"payment_failed\"}}");
        JsonNode customerLocked = json("{\"payload\":{\"kind\":\"customer_locked\"}}");
        RuleIndex emptySnapshot = manager.snapshot();

        String message = message(id, "payment_failed");
        publish(id, message);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(List.of(id), ids(manager.match(paymentFailed))));

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
                {"type":"rule.created","ruleId":"%s","version":1,"configVersion":12,"name":"Created from Control","enabled":true,"priority":100,"teamIds":[],"runbookId":null,"definition":{"v":1,"title":"Created from Control","match":{"path":"payload.kind","op":"eq","value":"%s"}}}
                """.formatted(id, kind);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private List<String> ids(List<com.goldman.alerting.engine.rules.Rule> rules) {
        return rules.stream().map(rule -> rule.id()).toList();
    }
}
