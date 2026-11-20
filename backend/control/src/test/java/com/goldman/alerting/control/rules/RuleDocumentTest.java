package com.goldman.alerting.control.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentTest {
    @Test
    void separatesRoutingFromTheDefinitionStoredAsJson() throws Exception {
        var draft = new RuleDocument().parse("""
                name: Timeout
                route: {teams: [payments]}
                match: {path: payload.code, op: eq, value: TIMEOUT}
                title: "Timeout"
                """);

        assertThat(draft.teams()).containsExactly("payments");
        assertThat(draft.definition().has("route")).isFalse();
        assertThat(draft.definition().at("/match/value").asText()).isEqualTo("TIMEOUT");
    }
}
