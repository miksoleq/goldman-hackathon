package com.goldman.alerting.control.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldman.alerting.contracts.LiveAlert;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AlertBodyTemplateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void substitutesOnlyStringValuesAndPreservesJsonTypesAndEscaping() throws Exception {
        var alert = new LiveAlert(1, "alert.created", UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(),
                List.of(), List.of(), "high", "quoted \"text\"\n$1 {{recipient}}", "body", 1, Instant.EPOCH, null, null);
        var data = new ChannelTransport.Alert(UUID.randomUUID(), alert, "D123", "https://control.test/alert");
        var template = json.readTree("""
                {"channel":"{{recipient}}","blocks":[{"text":"{{alert.title}}","number":3,"enabled":false}]}
                """);
        var rendered = json.readTree(AlertBodyTemplate.render(template, data).toString());
        assertThat(rendered.get("channel").asText()).isEqualTo("D123");
        assertThat(rendered.at("/blocks/0/text").textValue()).isEqualTo(alert.title());
        assertThat(rendered.at("/blocks/0/number").isInt()).isTrue();
        assertThat(rendered.at("/blocks/0/enabled").isBoolean()).isTrue();
        assertThat(template.get("channel").asText()).isEqualTo("{{recipient}}");
    }

    @Test void rejectsMisspelledAndIncompleteExpressionsAtApplyTime() {
        for (String invalid : List.of("{{alert.subject}}", "{{alert.id", "{{ }}")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    AlertBodyTemplate.validate(json.createObjectNode().put("text", invalid)));
        }
    }
}
