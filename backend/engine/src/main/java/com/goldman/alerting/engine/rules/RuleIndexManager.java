package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Transport-independent entry point for replacing or extending the active rule snapshot. */
public final class RuleIndexManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RuleIndexManager.class);
    private final java.util.concurrent.ConcurrentMap<String, Long> lastWarning = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicReference<RuleIndex> current;

    public RuleIndexManager(List<RuleDefinition> initialRules) {
        current = new AtomicReference<>(RuleIndex.compile(initialRules));
    }

    public RuleIndex snapshot() {
        return current.get();
    }

    /** Accepts a new or updated rule and atomically publishes the new snapshot. */
    public void accept(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        while (true) {
            RuleIndex previous = current.get();
            RuleIndex updated = previous.withRule(definition);
            if (current.compareAndSet(previous, updated)) {
                return;
            }
        }
    }

    /** Replaces all active rules after compiling a complete incoming snapshot. */
    public void replaceAll(List<RuleDefinition> definitions) {
        current.set(RuleIndex.compile(definitions));
    }

    public void remove(String id) {
        current.updateAndGet(previous -> previous.withoutRule(id));
        lastWarning.remove(id);
    }

    public RuleIndex.MatchResult matchWithDiagnostics(JsonNode event) {
        var result = current.get().matchWithDiagnostics(event);
        for (String id : result.evaluationErrorRuleIds()) {
            io.micrometer.core.instrument.Metrics.counter("rules.eval.errors", "rule", id).increment();
            long now = System.nanoTime();
            lastWarning.compute(id, (key, last) -> {
                if (last == null || now - last >= java.time.Duration.ofMinutes(1).toNanos()) {
                    log.warn("Could not evaluate rule ruleId={}", id);
                    return now;
                }
                return last;
            });
        }
        return result;
    }

    public List<Rule> match(JsonNode event) {
        return matchWithDiagnostics(event).matchedRules();
    }
}
