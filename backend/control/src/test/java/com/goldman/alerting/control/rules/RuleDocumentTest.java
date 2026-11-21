package com.goldman.alerting.control.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentTest {
    @Test
    void validatesAttachedInstructions() throws Exception {
        var parser = new RuleDocument();
        String rule = "name: Rule\ntitle: Alert\nmatch: {path: payload, op: exists}\nroute: {teams: [demo]}\nrunbook: ";
        assertThat(parser.parse(rule + "steps").runbook()).isEqualTo("steps");
        assertThat(parser.parse(rule + "{name: steps, format: text, content: Check}").inlineBook().content())
                .isEqualTo("Check");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(rule
                + "{name: steps, format: markdown, content: '{{bad}}'}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyOrDuplicateRecipients() throws Exception {
        var doc = new RuleDocument();
        for (String route : java.util.List.of("{}", "{teams: [], users: []}", "{users: [alice, alice]}", "{users: alice}")) {
            var yaml = "name: Demo\ntitle: Alert\nmatch: {path: payload, op: exists}\nroute: " + route;
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> doc.parse(yaml))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

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
