package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlertSpecTest {
    @Test void countsOnlyUnmappedSeverityValuesAndIsolatesEvaluationErrors() throws Exception {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(registry);
        try {
            var mapper = new ObjectMapper();
            var id = java.util.UUID.randomUUID().toString();
            var spec = AlertSpec.compile(id, "Rule", List.of(), List.of(),
                    mapper.readTree("{\"severity\":\"{{payload}}\"}"), null, null);
            for (String level : List.of("warning", "warn", "medium", "other")) {
                assertEquals("medium", spec.severity().apply(mapper.createObjectNode().put("payload", level)));
            }
            assertEquals(1, registry.get("rules.severity.unmapped").tag("rule", id).counter().count());
            var manager = new RuleIndexManager(List.of(new RuleDefinition(id, Condition.exists("payload"))));
            var bad = org.mockito.Mockito.mock(com.fasterxml.jackson.databind.JsonNode.class);
            org.mockito.Mockito.when(bad.isObject()).thenThrow(new IllegalStateException("unexpected value"));
            assertTrue(manager.match(bad).isEmpty());
            assertTrue(manager.match(bad).isEmpty());
            assertEquals(2, registry.get("rules.eval.errors").tag("rule", id).counter().count());
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    @Test void rendersFieldsAndNormalizesSeverity() throws Exception {
        var mapper = new ObjectMapper();
        var spec = AlertSpec.compile("id", "Timeout", List.of(), List.of(), mapper.readTree("""
                {"title":"{{rule.name}} w {{payload.service | lower}}",
                 "channels":["email","sms"],
                 "body":"Workflow {{payload.workflow}}: {{payload.reason | default: timeout}}",
                 "severity":"{{payload.level}}"}
                """), new com.goldman.alerting.contracts.Runbook(java.util.UUID.randomUUID(), "steps", "text",
                "Check {{payload.workflow}}: {{payload.reason | default: timeout}}"), null);
        var event = mapper.readTree("""
                {"payload":{"workflow":"WF-42","service":"PAYMENTS","level":"ERROR"}}
                """);
        assertEquals(List.of("email", "sms"), spec.channels());
        assertEquals("Timeout w payments", spec.title().apply(event));
        assertEquals("Workflow WF-42: timeout", spec.body().apply(event));
        assertEquals("Check WF-42: timeout", spec.runbook().apply(event).content());
        assertEquals("high", spec.severity().apply(event));
    }

    @Test void snapshotsActionParametersAndPreservesMissingValuesForControl() throws Exception {
        var mapper = new ObjectMapper();
        var step = new com.goldman.alerting.contracts.Runbook.Action("retry_workflow",
                java.util.Map.of("workflowId", "wf-{{payload.id}}"));
        var book = new com.goldman.alerting.contracts.Runbook(java.util.UUID.randomUUID(), "book", "text", "Check", List.of(step));
        var spec = AlertSpec.compile("id", "rule", List.of(), List.of(), mapper.createObjectNode(), book, book.id());
        assertEquals("wf-42", spec.runbook().apply(mapper.readTree("{\"payload\":{\"id\":42}}"))
                .actions().getFirst().params().get("workflowId"));
        assertEquals("", spec.runbook().apply(mapper.readTree("{\"payload\":{}}"))
                .actions().getFirst().params().get("workflowId"));
        assertTrue(mapper.readValue("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"legacy\",\"format\":\"text\",\"content\":\"Hi\"}",
                com.goldman.alerting.contracts.Runbook.class).actions().isEmpty());
    }
}
