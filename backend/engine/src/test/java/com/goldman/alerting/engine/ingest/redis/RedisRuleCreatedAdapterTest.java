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
    void disablingRemovesOnlyThatRuleAndPreventsStaleReactivation() {
        var manager = new RuleIndexManager(List.of());
        var adapter = new RedisRuleCreatedAdapter(objectMapper, manager, null, "rules.created.v1");
        String id = "048f06df-7fb4-4432-8743-87af3277489f";
        String other = "23ca15f5-1bcb-4883-ad5a-dd25e31ee77a";
        var event = json("{\"payload\":{\"kind\":\"payment\"}}");
        String enabled = message(id, true, "payment");
        String disabled = message(id, false, "payment").replace("\"version\":1", "\"version\":2");
        adapter.accept(enabled);
        adapter.accept(message(other, true, "payment"));
        var before = manager.snapshot();

        adapter.accept(disabled);
        adapter.accept(disabled);
        adapter.accept(enabled);
        assertEquals(List.of(other), manager.match(event).stream().map(rule -> rule.id()).toList());
        assertEquals(2, before.match(event).size());

        adapter.accept(enabled.replace("\"version\":1", "\"version\":3"));
        adapter.accept(disabled);
        assertEquals(2, manager.match(event).size());
    }

    @Test
    void acceptsValidRuleAndIgnoresDisabledOrInvalidMessages() {
        RuleIndexManager manager = new RuleIndexManager(List.of());
        RedisRuleCreatedAdapter adapter = new RedisRuleCreatedAdapter(objectMapper, manager, null, "rules.created.v1");

        adapter.accept(message("048f06df-7fb4-4432-8743-87af3277489f", true, "enabled"));
        assertEquals(1, manager.snapshot().ruleCount());
        assertEquals(List.of("048f06df-7fb4-4432-8743-87af3277489f"), manager.match(json("{\"payload\":{\"kind\":\"enabled\"}}"))
                .stream().map(rule -> rule.id()).toList());

        String updated = message("048f06df-7fb4-4432-8743-87af3277489f", true, "changed").replace("\"version\":1", "\"version\":2");
        adapter.accept(updated);
        var snapshot = manager.snapshot();
        adapter.accept(updated);
        adapter.accept(message("048f06df-7fb4-4432-8743-87af3277489f", true, "enabled"));
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
