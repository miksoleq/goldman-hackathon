package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventShapeTest {
    @Test
    void matchesRequiredNestedArrayItemsWithoutCoercion() throws Exception {
        var mapper = new ObjectMapper();
        var match = mapper.readTree("""
                {"path":"payload","op":"jsonSchema","value":{"type":"array","items":{
                 "type":"object","required":["count"],"properties":{"count":{"type":"integer"}}}}}
                """);
        var index = RuleIndex.compile(List.of(new RuleDefinition("counts", ConditionParser.parse(match))));
        assertEquals(1, index.alwaysCheckCount());
        assertEquals(1, index.match(mapper.readTree("{\"payload\":[{\"count\":2}]}")).size());
        for (String payload : List.of("null", "{}", "[{}]", "[{\"count\":\"2\"}]", "[{\"count\":1},false]")) {
            assertTrue(index.match(mapper.readTree("{\"payload\":" + payload + "}")).isEmpty());
        }
        assertTrue(index.match(mapper.readTree("{}")).isEmpty());
    }
}
