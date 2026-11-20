package com.goldman.alerting.engine.ingest.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaRuleCreatedAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsValidRuleAndIgnoresDisabledOrInvalidMessages() {
        RuleIndexManager manager = new RuleIndexManager(List.of());
        KafkaRuleCreatedAdapter adapter = new KafkaRuleCreatedAdapter(objectMapper, manager);

        adapter.onMessage(record(message("enabled-rule", true, "enabled")));
        assertEquals(1, manager.snapshot().ruleCount());
        assertEquals(List.of("enabled-rule"), manager.match(json("{\"payload\":{\"kind\":\"enabled\"}}"))
                .stream().map(rule -> rule.id()).toList());

        adapter.onMessage(record(message("disabled-rule", false, "disabled")));
        adapter.onMessage(record("not-json"));

        assertEquals(1, manager.snapshot().ruleCount());
        assertTrue(manager.match(json("{\"payload\":{\"kind\":\"disabled\"}}")).isEmpty());
    }

    private String message(String id, boolean enabled, String kind) {
        return """
                {"type":"rule.created","ruleId":"%s","version":1,"configVersion":1,"name":"Rule","enabled":%s,"priority":100,"teamIds":[],"runbookId":null,"definition":{"v":1,"title":"Rule","match":{"path":"payload.kind","op":"eq","value":"%s"}}}
                """.formatted(id, enabled, kind);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("rules.created.v1", 0, 0L, null, value);
    }

    private com.fasterxml.jackson.databind.JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
