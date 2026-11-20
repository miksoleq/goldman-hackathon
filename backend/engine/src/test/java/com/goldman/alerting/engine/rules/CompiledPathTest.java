package com.goldman.alerting.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompiledPathTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesNestedFieldsAndArrayWildcards() throws Exception {
        CompiledPath path = CompiledPath.compile("payload.items[*].sku");
        JsonNode event = objectMapper.readTree("""
                {"payload":{"items":[{"sku":"A-1"},{"sku":"B-2"}]}}
                """);
        List<String> values = new ArrayList<>();

        path.forEachValue(event, value -> values.add(value.asText()));

        assertEquals(List.of("A-1", "B-2"), values);
    }

    @Test
    void supportsQuotedKeysArrayIndexesAndMetaRoot() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"payload":{"user.name":"Ada","items":[{"sku":"A-1"}]},"meta":{"source":"payments"}}
                """);

        assertEquals("Ada", CompiledPath.compile("payload['user.name']").get(event).asText());
        assertEquals("A-1", CompiledPath.compile("payload.items[0].sku").get(event).asText());
        assertEquals("payments", CompiledPath.compile("meta.source").get(event).asText());
    }

    @Test
    void missingNullAndWrongShapeResolveAsNoValues() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"payload":{"present":null,"items":"not-an-array"}}
                """);

        CompiledPath missing = CompiledPath.compile("payload.missing.value");
        CompiledPath nullValue = CompiledPath.compile("payload.present");
        CompiledPath wrongShape = CompiledPath.compile("payload.items[*].sku");

        assertNull(missing.get(event));
        assertFalse(missing.exists(event));
        assertFalse(nullValue.exists(event));
        assertFalse(wrongShape.anyMatch(event, JsonNode::isValueNode));
    }

    @Test
    void exposesSegmentsWithoutAllowingMutation() {
        CompiledPath path = CompiledPath.compile("payload.customer.id");
        String[] exposed = path.segments();
        exposed[0] = "meta";

        assertEquals("payload", path.segments()[0]);
    }
}
