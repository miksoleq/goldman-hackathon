package com.goldman.alerting.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RuleCreatedEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesControlEnvelopeAndV1Definition() {
        RuleCreatedEvent event = RuleCreatedEvent.fromJson(message("048f06df-7fb4-4432-8743-87af3277489f", true, "payment_failed"), objectMapper);

        assertEquals("048f06df-7fb4-4432-8743-87af3277489f", event.ruleId());
        assertTrue(event.enabled());
        assertEquals("048f06df-7fb4-4432-8743-87af3277489f", event.toRuleDefinition().id());
        assertEquals("payment_failed", event.definition().path("match").path("value").asText());
    }

    @Test
    void preservesDisabledStateForAdapterToIgnore() {
        RuleCreatedEvent event = RuleCreatedEvent.fromJson(message("rule-disabled", false, "payment_failed"), objectMapper);

        assertFalse(event.enabled());
    }

    @Test
    void rejectsMalformedJsonAndNonObjectPayloads() {
        assertThrows(InvalidRuleDefinitionException.class,
                () -> RuleCreatedEvent.fromJson("{", objectMapper));
        assertThrows(InvalidRuleDefinitionException.class,
                () -> RuleCreatedEvent.fromJson("[]", objectMapper));
    }

    @Test
    void rejectsMissingOrInvalidEnvelopeFields() {
        assertInvalid("{\"type\":\"rule.created\",\"enabled\":true,\"definition\":{}}", "rule id");
        assertInvalid("{\"type\":\"rule.created\",\"ruleId\":\"rule-1\",\"enabled\":\"true\",\"definition\":{}}", "enabled type");
        assertInvalid("{\"type\":\"rule.created\",\"ruleId\":\"rule-1\",\"enabled\":true,\"definition\":null}", "definition type");
        assertInvalid("{\"type\":\"rule.updated\",\"ruleId\":\"rule-1\",\"enabled\":true,\"definition\":{}}", "event type");
        RuleCreatedEvent invalidVersion = RuleCreatedEvent.fromJson(
                "{\"type\":\"rule.created\",\"ruleId\":\"rule-1\",\"enabled\":true,\"definition\":{\"v\":2}}", objectMapper);
        assertThrows(InvalidRuleDefinitionException.class, invalidVersion::toRuleDefinition, "definition version");
    }

    @Test
    void doesNotExposeMutableDefinitionState() throws Exception {
        JsonNode definition = objectMapper.readTree("{\"v\":1,\"title\":\"T\",\"match\":{\"path\":\"payload.kind\",\"op\":\"eq\",\"value\":\"a\"}} ");
        RuleCreatedEvent event = new RuleCreatedEvent("rule-1", true, definition);
        ((ObjectNode) event.definition()).put("title", "mutated");

        assertEquals("T", event.definition().path("title").asText());
    }

    private void assertInvalid(String payload, String description) {
        assertThrows(InvalidRuleDefinitionException.class,
                () -> RuleCreatedEvent.fromJson(payload, objectMapper), description);
    }

    private String message(String id, boolean enabled, String kind) {
        return """
                {"type":"rule.created","ruleId":"%s","version":1,"configVersion":17,"name":"Payment","enabled":%s,"priority":100,"teamIds":[],"runbookId":null,"definition":{"v":1,"title":"Payment","match":{"path":"payload.kind","op":"eq","value":"%s"}}}
                """.formatted(id, enabled, kind);
    }
}
