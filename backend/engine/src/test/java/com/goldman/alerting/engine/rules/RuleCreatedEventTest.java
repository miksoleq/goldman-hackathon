package com.goldman.alerting.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldman.alerting.contracts.RuleCreated;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RuleCreatedEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test void validatesVersionTemplateAndOversizedIndexOnSnapshotLoad() throws Exception {
        var node = (ObjectNode) objectMapper.readTree(message("048f06df-7fb4-4432-8743-87af3277489f", true, "x"));
        var definition = (ObjectNode) node.get("definition");
        definition.putObject("match").put("path", "payload[2147483648]").put("op", "exists").put("value", false);
        var index = RuleIndex.compile(java.util.List.of(RuleDefinition.fromEvent(RuleCreated.fromTree(node))));
        assertEquals(1, index.match(objectMapper.readTree("{\"payload\":[1]}")).size());
        definition.put("v", 4294967297L);
        assertThrows(IllegalArgumentException.class, () -> RuleDefinition.fromEvent(RuleCreated.fromTree(node)));
        definition.put("v", 1).put("title", "{{payload.x | typo}}");
        assertThrows(IllegalArgumentException.class, () -> RuleDefinition.fromEvent(RuleCreated.fromTree(node)));
    }

    @Test
    void parsesControlEnvelopeAndV1Definition() throws Exception {
        RuleCreated event = parse(message("048f06df-7fb4-4432-8743-87af3277489f", true, "payment_failed"));

        assertEquals("048f06df-7fb4-4432-8743-87af3277489f", event.ruleId());
        assertTrue(event.enabled());
        assertEquals("048f06df-7fb4-4432-8743-87af3277489f", RuleDefinition.fromEvent(event).id());
        assertEquals("payment_failed", event.definition().path("match").path("value").asText());
    }

    @Test
    void preservesDisabledStateForAdapterToIgnore() throws Exception {
        RuleCreated event = parse(message("rule-disabled", false, "payment_failed"));

        assertFalse(event.enabled());
    }

    @Test
    void rejectsMalformedJsonAndNonObjectPayloads() {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> parse("{"));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> parse("[]"));
    }

    @Test
    void rejectsMissingOrInvalidEnvelopeFields() throws Exception {
        assertInvalid("{\"type\":\"rule.created\",\"enabled\":true,\"definition\":{}}", "rule id");
        assertInvalid("{\"type\":\"rule.created\",\"ruleId\":\"rule-1\",\"enabled\":\"true\",\"definition\":{}}", "enabled type");
        assertInvalid("{\"type\":\"rule.created\",\"ruleId\":\"rule-1\",\"enabled\":true,\"definition\":null}", "definition type");
        assertInvalid("{\"type\":\"rule.updated\",\"ruleId\":\"rule-1\",\"enabled\":true,\"definition\":{}}", "event type");
        RuleCreated invalidVersion = parse(
                "{\"type\":\"rule.created\",\"ruleId\":\"rule-1\",\"enabled\":true,\"definition\":{\"v\":2}}");
        assertThrows(InvalidRuleDefinitionException.class, () -> RuleDefinition.fromEvent(invalidVersion), "definition version");
    }

    @Test
    void doesNotExposeMutableDefinitionState() throws Exception {
        JsonNode definition = objectMapper.readTree("{\"v\":1,\"title\":\"T\",\"match\":{\"path\":\"payload.kind\",\"op\":\"eq\",\"value\":\"a\"}} ");
        RuleCreated event = new RuleCreated(RuleCreated.TYPE, "rule-1", 1, 2, "rule", true, 100,
                java.util.List.of(), java.util.List.of(), null, definition, null);
        ((ObjectNode) event.definition()).put("title", "mutated");

        assertEquals("T", event.definition().path("title").asText());
    }

    private RuleCreated parse(String raw) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.readValue(raw, RuleCreated.class);
    }

    private void assertInvalid(String payload, String description) {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> parse(payload), description);
    }

    private String message(String id, boolean enabled, String kind) {
        return """
                {"type":"rule.created","ruleId":"%s","version":1,"configVersion":17,"name":"Payment","enabled":%s,"priority":100,"teamIds":[],"runbookId":null,"definition":{"v":1,"title":"Payment","match":{"path":"payload.kind","op":"eq","value":"%s"}}}
                """.formatted(id, enabled, kind);
    }
}
