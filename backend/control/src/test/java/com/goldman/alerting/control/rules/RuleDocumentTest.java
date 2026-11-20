package com.goldman.alerting.control.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentTest {
    @Test
    void rejectsInvalidNestedShapeBeforePersistence() throws Exception {
        var document = new RuleDocument();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> document.parse("""
                name: Bad shape
                route: {teams: [payments]}
                match: {path: payload, op: jsonSchema, value: {type: array, items: {type: typo}}}
                title: Bad
                """)).isInstanceOf(IllegalArgumentException.class);
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
