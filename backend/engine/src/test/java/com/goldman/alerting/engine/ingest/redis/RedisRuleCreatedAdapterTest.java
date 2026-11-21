package com.goldman.alerting.engine.ingest.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.engine.rules.RuleIndexManager;
import java.util.List;

import org.junit.jupiter.api.Test;

class RedisRuleCreatedAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsValidRuleAndIgnoresDisabledOrInvalidMessages() {
        RuleIndexManager manager = new RuleIndexManager(List.of());
        RedisRuleCreatedAdapter adapter = new RedisRuleCreatedAdapter(objectMapper, manager, null, "rules.created.v1");

        adapter.accept(message("enabled-rule", true, "enabled"));
        assertEquals(1, manager.snapshot().ruleCount());
        assertEquals(List.of("enabled-rule"), manager.match(json("{\"payload\":{\"kind\":\"enabled\"}}"))
                .stream().map(rule -> rule.id()).toList());

        String updated = message("enabled-rule", true, "changed").replace("\"version\":1", "\"version\":2");
        adapter.accept(updated);
        var snapshot = manager.snapshot();
        adapter.accept(updated);
        adapter.accept(message("enabled-rule", true, "enabled"));
        org.junit.jupiter.api.Assertions.assertSame(snapshot, manager.snapshot());
        assertEquals(1, manager.match(json("{\"payload\":{\"kind\":\"changed\"}}")).size());

        adapter.accept(message("disabled-rule", false, "disabled"));
        adapter.accept("not-json");

        assertEquals(1, manager.snapshot().ruleCount());
        assertTrue(manager.match(json("{\"payload\":{\"kind\":\"disabled\"}}")).isEmpty());
    }

    private String message(String id, boolean enabled, String kind) {
        return """
                {"type":"rule.created","ruleId":"%s","version":1,"configVersion":1,"name":"Rule","enabled":%s,"priority":100,"teamIds":[],"runbookId":null,"definition":{"v":1,"title":"Rule","match":{"path":"payload.kind","op":"eq","value":"%s"}}}
                """.formatted(id, enabled, kind);
    }

    private com.fasterxml.jackson.databind.JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
