package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlertSpecTest {
    @Test void rendersFieldsAndNormalizesSeverity() throws Exception {
        var mapper = new ObjectMapper();
        var spec = AlertSpec.compile("id", "Timeout", List.of(), List.of(), mapper.readTree("""
                {"title":"{{rule.name}} w {{payload.service | lower}}",
                 "body":"Workflow {{payload.workflow}}: {{payload.reason | default: timeout}}",
                 "severity":"{{payload.level}}"}
                """), new com.goldman.alerting.contracts.Runbook(java.util.UUID.randomUUID(), "steps", "text",
                "Check {{payload.workflow}}: {{payload.reason | default: timeout}}"));
        var event = mapper.readTree("""
                {"payload":{"workflow":"WF-42","service":"PAYMENTS","level":"ERROR"}}
                """);
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
        var spec = AlertSpec.compile("id", "rule", List.of(), List.of(), mapper.createObjectNode(), book);
        assertEquals("wf-42", spec.runbook().apply(mapper.readTree("{\"payload\":{\"id\":42}}"))
                .actions().getFirst().params().get("workflowId"));
        assertEquals("", spec.runbook().apply(mapper.readTree("{\"payload\":{}}"))
                .actions().getFirst().params().get("workflowId"));
        assertTrue(mapper.readValue("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"legacy\",\"format\":\"text\",\"content\":\"Hi\"}",
                com.goldman.alerting.contracts.Runbook.class).actions().isEmpty());
    }
}
