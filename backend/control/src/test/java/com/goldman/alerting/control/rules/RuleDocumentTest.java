package com.goldman.alerting.control.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentTest {
    @Test void readsLiteralPythonStepsAndCapsTheirTimeout() throws Exception {
        var draft = new RuleDocument().parse("""
                name: Diagnostic
                title: Alert
                match: {path: payload, op: exists}
                route: {teams: [demo]}
                runbook:
                  name: steps
                  steps:
                    - action: python
                      approval: none
                      timeoutMs: 7000
                      code: |
                        def run(ctx):
                            return "{{literal Python braces}}"
                """);
        var action = draft.inlineBook().actions().getFirst();
        assertThat(action.code()).contains("{{literal Python braces}}");
        assertThat(action.params()).isEmpty();
        assertThat(action.approval()).isEqualTo("none");
        assertThat(action.timeoutMs()).isEqualTo(5000);
    }

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

    @Test void appliesNamedRuleResourcesWithTheSameValidationAsPlainRules() throws Exception {
        var parser = new RuleDocument();
        var doc = """
                kind: Rule
                name: apply-demo
                spec:
                  title: Alert
                  route: {teams: [demo]}
                  match: {path: payload, op: exists}
                """;
        assertThat(parser.parse(doc).name()).isEqualTo("apply-demo");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(doc.replace("kind: Rule", "kind: Team")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
