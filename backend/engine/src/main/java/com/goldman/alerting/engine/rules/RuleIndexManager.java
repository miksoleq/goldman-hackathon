package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Transport-independent entry point for replacing or extending the active rule snapshot. */
public final class RuleIndexManager {

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

    public RuleIndex.MatchResult matchWithDiagnostics(JsonNode event) {
        return current.get().matchWithDiagnostics(event);
    }

    public List<Rule> match(JsonNode event) {
        return current.get().match(event);
    }
}
