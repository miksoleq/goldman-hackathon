package com.goldman.alerting.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleIndexTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void indexesOneEqualityForAllAndStillChecksEveryOtherCondition() throws Exception {
        RuleIndex index = RuleIndex.compile(List.of(rule(
                "large-payment",
                Condition.all(
                        Condition.eq("payload.type", "payment_failed"),
                        Condition.gt("payload.amount", IntNode.valueOf(1000))
                )
        )));

        RuleIndex.MatchResult rejected = index.matchWithDiagnostics(json("""
                {"payload":{"type":"payment_failed","amount":500}}
                """));
        RuleIndex.MatchResult accepted = index.matchWithDiagnostics(json("""
                {"payload":{"type":"payment_failed","amount":1500}}
                """));

        assertEquals(1, rejected.candidateCount());
        assertTrue(rejected.matchedRules().isEmpty());
        assertEquals(List.of("large-payment"), ids(accepted.matchedRules()));
    }

    @Test
    void indexesEveryOrBranchWhenEachBranchHasEquality() throws Exception {
        RuleIndex index = RuleIndex.compile(List.of(rule(
                "payments-or-timeouts",
                Condition.any(
                        Condition.all(
                                Condition.eq("payload.type", "payment_failed"),
                                Condition.gt("payload.amount", IntNode.valueOf(1000))
                        ),
                        Condition.all(
                                Condition.eq("payload.error.code", "TIMEOUT"),
                                Condition.exists("payload.error.message")
                        )
                )
        )));

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{"error":{"code":"TIMEOUT","message":"provider"}}}
                """));

        assertEquals(1, result.candidateCount());
        assertEquals(List.of("payments-or-timeouts"), ids(result.matchedRules()));
        assertEquals(0, index.alwaysCheckCount());
    }

    @Test
    void sendsWholeOrRuleToAlwaysCheckWhenOneBranchHasNoEquality() throws Exception {
        RuleIndex index = RuleIndex.compile(List.of(rule(
                "broad-or",
                Condition.any(
                        Condition.eq("payload.type", "payment_failed"),
                        Condition.gt("payload.amount", IntNode.valueOf(1000))
                )
        )));

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{"amount":1200}}
                """));

        assertEquals(1, index.alwaysCheckCount());
        assertEquals(1, result.candidateCount());
        assertEquals(List.of("broad-or"), ids(result.matchedRules()));
    }

    @Test
    void indexesInValuesAndBitSetDeduplicatesOrBranchCandidates() throws Exception {
        RuleIndex index = RuleIndex.compile(List.of(rule(
                "kind-or-alias",
                Condition.any(
                        Condition.in("payload.kind", List.of(TextNode.valueOf("a"), TextNode.valueOf("b"))),
                        Condition.eq("payload.alias", "a")
                )
        )));

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{"kind":"a","alias":"a"}}
                """));
        RuleIndex.MatchResult secondInValue = index.matchWithDiagnostics(json("""
                {"payload":{"kind":"b"}}
                """));

        assertEquals(1, result.candidateCount());
        assertEquals(List.of("kind-or-alias"), ids(result.matchedRules()));
        assertEquals(1, secondInValue.candidateCount());
        assertEquals(List.of("kind-or-alias"), ids(secondInValue.matchedRules()));
    }

    @Test
    void choosesEqualityThenPrefersHigherCardinalityAndNonEnvironmentPaths() throws Exception {
        RuleIndex equalityBeforeIn = RuleIndex.compile(List.of(rule(
                "prefer-eq",
                Condition.all(
                        Condition.in("payload.accountId", List.of(TextNode.valueOf("42"), TextNode.valueOf("43"))),
                        Condition.eq("payload.environment", "prod")
                )
        )));
        RuleIndex nonEnvironment = RuleIndex.compile(List.of(rule(
                "prefer-account-id",
                Condition.all(
                        Condition.eq("payload.environment", "prod"),
                        Condition.eq("payload.accountId", "42")
                )
        )));

        RuleIndex.MatchResult eqPreferred = equalityBeforeIn.matchWithDiagnostics(json("""
                {"payload":{"accountId":"42","environment":"test"}}
                """));
        RuleIndex.MatchResult pathPreferred = nonEnvironment.matchWithDiagnostics(json("""
                {"payload":{"accountId":"42","environment":"test"}}
                """));

        assertEquals(0, eqPreferred.candidateCount());
        assertEquals(1, pathPreferred.candidateCount());
    }

    @Test
    void supportsJsonSchemaPathsWildcardsNullSemanticsAndDeepEquality() throws Exception {
        Condition condition = ConditionParser.parse(json("""
                {"all":[
                  {"path":"payload.items[*].sku","op":"eq","value":"A-1","ignoreCase":true},
                  {"path":"payload['user.name']","op":"eq","value":"ada","ignoreCase":true},
                  {"path":"meta.source","op":"eq","value":"payments"},
                  {"path":"payload.amount","op":"eq","value":1.0},
                  {"path":"payload.missing","op":"exists","value":false}
                ]}
                """));
        RuleIndex index = RuleIndex.compile(List.of(rule("schema-paths", condition)));

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{"items":[{"sku":"b-2"},{"sku":"a-1"}],"user.name":"Ada","amount":1},"meta":{"source":"payments"}}
                """));

        assertEquals(1, result.candidateCount());
        assertEquals(List.of("schema-paths"), ids(result.matchedRules()));
    }

    @Test
    void handlesNumericStringComparisonsExistsAndRegexFindWithRe2J() throws Exception {
        Condition condition = ConditionParser.parse(json("""
                {"all":[
                  {"path":"payload.amount","op":"gt","value":100},
                  {"path":"payload.amount","op":"lte","value":200},
                  {"path":"payload.name","op":"exists"},
                  {"path":"payload.ref","op":"regex","value":"INV-[0-9]+"}
                ]}
                """));
        RuleIndex index = RuleIndex.compile(List.of(rule("invoice", condition)));

        RuleIndex.MatchResult matched = index.matchWithDiagnostics(json("""
                {"payload":{"amount":"150.50","name":"Mira","ref":"prefix INV-42 suffix"}}
                """));
        RuleIndex.MatchResult wrongTypes = index.matchWithDiagnostics(json("""
                {"payload":{"amount":{"value":150},"name":null,"ref":[]}}
                """));

        assertEquals(List.of("invoice"), ids(matched.matchedRules()));
        assertTrue(wrongTypes.matchedRules().isEmpty());
        assertEquals(0, matched.evaluationErrorCount());
        assertEquals(0, wrongTypes.evaluationErrorCount());
    }

    @Test
    void supportsNegativeMembershipContainsTextOperatorsAndCaseFolding() throws Exception {
        Condition condition = ConditionParser.parse(json("""
                {"all":[
                  {"path":"payload.mode","op":"nin","value":["test","sandbox"],"ignoreCase":true},
                  {"path":"payload.tags[*]","op":"contains","value":"CARD","ignoreCase":true},
                  {"path":"payload.reference","op":"startsWith","value":"inv-","ignoreCase":true},
                  {"path":"payload.message","op":"endsWith","value":"FAILED","ignoreCase":true}
                ]}
                """));
        RuleIndex index = RuleIndex.compile(List.of(rule("text-operators", condition)));

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{"mode":"prod","tags":["wire","card"],"reference":"INV-42","message":"Payment FAILED"}}
                """));
        RuleIndex.MatchResult nullNin = index.matchWithDiagnostics(json("""
                {"payload":{"tags":["card"],"reference":"INV-42","message":"Payment FAILED"}}
                """));

        assertEquals(List.of("text-operators"), ids(result.matchedRules()));
        assertTrue(nullNin.matchedRules().isEmpty());
    }

    @Test
    void notRootAlwaysChecksAndCanMatchWhenItsPathIsMissing() throws Exception {
        RuleIndex index = RuleIndex.compile(List.of(rule(
                "not-test-environment",
                Condition.not(Condition.eq("payload.environment", "test"))
        )));

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{}}
                """));

        assertEquals(1, index.alwaysCheckCount());
        assertEquals(List.of("not-test-environment"), ids(result.matchedRules()));
    }

    @Test
    void rejectsUnknownVersionsInvalidRegexesAndInvalidConditionShapes() throws Exception {
        assertThrows(InvalidRuleDefinitionException.class, () -> RuleDefinition.fromV1(
                "bad-version",
                json("""
                        {"v":2,"title":"Bad version","match":{"path":"payload.x","op":"eq","value":1}}
                        """)));
        assertThrows(InvalidRuleDefinitionException.class, () -> ConditionParser.parse(json("""
                {"all":[],"path":"payload.x"}
                """)));
        assertThrows(InvalidRuleDefinitionException.class, () -> ConditionParser.parse(json("""
                {"path":"error.code","op":"eq","value":"x"}
                """)));
        assertThrows(InvalidRuleDefinitionException.class, () -> RuleIndex.compile(List.of(rule(
                "bad-regex",
                Condition.regex("payload.message", "(?=bad)")
        ))));
    }

    @Test
    void addingRulePublishesNewSnapshotWithoutMutatingReadersOfOldSnapshot() throws Exception {
        RuleIndexManager manager = new RuleIndexManager(List.of(rule("first", Condition.eq("payload.type", "first"))));
        RuleIndex oldSnapshot = manager.snapshot();

        manager.accept(rule("second", Condition.eq("payload.type", "second")));

        RuleIndex newSnapshot = manager.snapshot();
        assertNotSame(oldSnapshot, newSnapshot);
        assertEquals(1, oldSnapshot.ruleCount());
        assertEquals(2, newSnapshot.ruleCount());
        assertTrue(oldSnapshot.match(json("{\"payload\":{\"type\":\"second\"}}")).isEmpty());
        assertEquals(List.of("second"), ids(manager.match(json("{\"payload\":{\"type\":\"second\"}}"))));
    }

    @Test
    void fiveThousandRulesEvaluateOnlyMatchingBuckets() throws Exception {
        List<RuleDefinition> rules = new ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            rules.add(rule("customer-" + i, Condition.eq("payload.customerId", "customer-" + i)));
        }
        RuleIndex index = RuleIndex.compile(rules);

        RuleIndex.MatchResult result = index.matchWithDiagnostics(json("""
                {"payload":{"customerId":"customer-4242"}}
                """));

        assertEquals(5000, index.ruleCount());
        assertEquals(1, result.candidateCount());
        assertTrue(result.candidateCount() < index.ruleCount() / 100);
        assertEquals(List.of("customer-4242"), ids(result.matchedRules()));
    }

    private RuleDefinition rule(String id, Condition condition) {
        return new RuleDefinition(id, condition);
    }

    private JsonNode json(String source) throws Exception {
        return objectMapper.readTree(source);
    }

    private List<String> ids(List<Rule> rules) {
        return rules.stream().map(Rule::id).toList();
    }
}
