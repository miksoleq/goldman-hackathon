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
}
