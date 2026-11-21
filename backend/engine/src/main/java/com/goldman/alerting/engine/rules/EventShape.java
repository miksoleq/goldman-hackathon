package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.util.function.Predicate;

/** Compiles the structural JSON Schema subset declared in the rule contract. */
public final class EventShape {
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final JsonSchema CONTRACT = loadContract();

    private EventShape() {}

    private static JsonSchema loadContract() {
        var mapper = new ObjectMapper();
        try (var input = EventShape.class.getResourceAsStream("/schemas/rule-definition.v1.schema.json")) {
            var schema = mapper.createObjectNode().put("$ref", "#/$defs/shape");
            schema.set("$defs", mapper.readTree(input).get("$defs"));
            return FACTORY.getSchema(schema);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Predicate<JsonNode> compile(JsonNode shape) {
        if (shape == null || !CONTRACT.validate(shape).isEmpty()) {
            throw new IllegalArgumentException("Invalid structural JSON Schema");
        }
        JsonSchema compiled = FACTORY.getSchema(shape.deepCopy());
        return value -> compiled.validate(value).isEmpty();
    }
}
