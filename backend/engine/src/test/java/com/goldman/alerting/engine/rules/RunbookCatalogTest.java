package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.RuleCreated;
import com.goldman.alerting.contracts.Runbook;
import com.goldman.alerting.contracts.RunbookCandidate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunbookCatalogTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunbookCatalog catalog = new RunbookCatalog();
    private final UUID ruleId = UUID.randomUUID();

    private Rule rule(String mode, Runbook pinned) throws Exception {
        var definition = mapper.readTree("""
                {"v":1,"title":"Test","match":{"path":"payload","op":"exists"},"runbookMode":"%s"}
                """.formatted(mode));
        var created = new RuleCreated(RuleCreated.TYPE, ruleId.toString(), 1, 1, "Payments", true, 10,
                List.of(), List.of(), pinned == null ? null : pinned.id(), definition, pinned);
        return new Rule(0, RuleDefinition.fromEvent(created), event -> true);
    }

    private RunbookCandidate book(String name, String match) throws Exception {
        return new RunbookCandidate(new Runbook(UUID.randomUUID(), name, "text", "{{rule.name}} {{payload.code}}"),
                mapper.readTree(match));
    }

    @Test void pinnedWinsAndNoneSkipsEvenWhenTheCatalogMatches() throws Exception {
        catalog.replace(List.of(book("Alpha", "{\"path\":\"payload.code\",\"op\":\"exists\"}")));
        JsonNode event = mapper.readTree("{\"payload\":{\"code\":42}}");
        var pinned = new Runbook(UUID.randomUUID(), "Zulu", "text", "Pinned {{payload.code}}");
        var result = catalog.select(rule("auto", pinned), event);
        assertEquals(pinned.id(), result.id());
        assertEquals("Pinned 42", result.content());
        assertNull(catalog.select(rule("none", null), event));
    }

    @Test void autoUsesSharedConditionsAndTheFirstMatchingNameRegardlessOfInputOrder() throws Exception {
        var alpha = book("alpha", "{\"all\":[{\"path\":\"meta.source\",\"op\":\"eq\",\"value\":\"logs\"}]}");
        var beta = book("Beta", "{\"payload.code\":42}");
        var invalid = book("AAA", "{\"path\":\"payload\",\"op\":\"made-up\"}");
        var rule = rule("auto", null);
        var event = mapper.readTree("{\"meta\":{\"source\":\"logs\"},\"payload\":{\"code\":42}}");
        for (var order : List.of(List.of(invalid, beta, alpha), List.of(alpha, beta))) {
            catalog.replace(order);
            var winner = catalog.select(rule, event);
            assertEquals(alpha.runbook().id(), winner.id());
            assertEquals("Payments 42", winner.content());
        }
        catalog.replace(List.of(beta));
        assertEquals(beta.runbook().id(), catalog.select(rule, event).id());
        assertNull(catalog.select(rule, mapper.readTree("{\"payload\":{}}")));
        catalog.replace(List.of());
        assertNull(catalog.select(rule, event));
    }
}
