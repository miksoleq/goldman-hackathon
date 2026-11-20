package com.goldman.alerting.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class RuleIndexIntegrationIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesV1DefinitionBuildsBucketsMatchesEventsAndAcceptsHotUpdates() throws Exception {
        RuleDefinition initialRule = RuleDefinition.fromV1("rule-1", json("""
                {
                  "v": 1,
                  "title": "Declined payment",
                  "match": {"all":[
                    {"path":"payload.type","op":"eq","value":"payment_failed"},
                    {"path":"payload.amount","op":"gt","value":1000},
                    {"path":"meta.source","op":"eq","value":"payments"},
                    {"path":"payload.message","op":"regex","value":"(?i)declined|timeout"}
                  ]}
                }
                """));
        RuleIndexManager manager = new RuleIndexManager(List.of(initialRule));
        RuleIndex initialSnapshot = manager.snapshot();
        JsonNode declinedPayment = json("""
                {"payload":{"type":"payment_failed","amount":"1500.25","message":"Card DECLINED"},"meta":{"source":"payments"}}
                """);

        RuleIndex.MatchResult initialMatch = manager.matchWithDiagnostics(declinedPayment);

        assertEquals(List.of("rule-1"), ids(initialMatch.matchedRules()));
        assertEquals(1, initialMatch.candidateCount());

        manager.accept(RuleDefinition.fromV1("rule-2", json("""
                {
                  "v": 1,
                  "title": "Customer locked",
                  "match": {"path":"payload.type","op":"eq","value":"customer_locked"}
                }
                """)));

        JsonNode customerLocked = json("""
                {"payload":{"type":"customer_locked"},"meta":{"source":"accounts"}}
                """);
        assertEquals(1, initialSnapshot.ruleCount());
        assertEquals(2, manager.snapshot().ruleCount());
        assertTrue(initialSnapshot.match(customerLocked).isEmpty());
        assertEquals(List.of("rule-2"), ids(manager.match(customerLocked)));
    }

    @Test
    void concurrentReadersSeeOnlyCompleteImmutableSnapshotsDuringRuleCreation() throws Exception {
        RuleIndexManager manager = new RuleIndexManager(List.of(rule("rule-1")));
        JsonNode event = json("{\"payload\":{\"type\":\"event\"}}");
        int readerCount = 4;
        CountDownLatch ready = new CountDownLatch(readerCount);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService readers = Executors.newFixedThreadPool(readerCount)) {
            List<Future<?>> results = new ArrayList<>();
            for (int reader = 0; reader < readerCount; reader++) {
                results.add(readers.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int iteration = 0; iteration < 2000; iteration++) {
                        RuleIndex.MatchResult match = manager.matchWithDiagnostics(event);
                        if (match.candidateCount() != match.matchedRules().size()
                                || match.matchedRules().isEmpty() || match.matchedRules().size() > 2) {
                            throw new AssertionError("Reader observed a partial rule-index snapshot");
                        }
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            manager.accept(rule("rule-2"));
            for (Future<?> result : results) {
                result.get();
            }
        }
        assertEquals(2, manager.snapshot().ruleCount());
    }

    private RuleDefinition rule(String id) {
        return RuleDefinition.fromV1(id, json("""
                {"v":1,"title":"Event","match":{"path":"payload.type","op":"eq","value":"event"}}
                """));
    }

    private JsonNode json(String source) {
        try {
            return objectMapper.readTree(source);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid test JSON", exception);
        }
    }

    private List<String> ids(List<Rule> rules) {
        return rules.stream().map(Rule::id).toList();
    }
}
