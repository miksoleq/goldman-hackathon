package com.goldman.alerting.control.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{teams: [], users: []}",
            "{users: anna}", "{users: [anna, anna]}", "{people: [anna]}"})
    void rejectsInvalidRecipients(String route) throws Exception {
        var parser = new RuleDocument();
        String source = "name: Demo\ntitle: Demo\nmatch: {path: payload, op: exists}\nroute: " + route;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(source))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
