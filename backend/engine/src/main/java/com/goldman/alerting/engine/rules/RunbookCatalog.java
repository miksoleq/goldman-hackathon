package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.goldman.alerting.contracts.Runbook;
import com.goldman.alerting.contracts.RunbookCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RunbookCatalog {
    private static final Logger log = LoggerFactory.getLogger(RunbookCatalog.class);
    private record Entry(Runbook book, Predicate<JsonNode> matches, AlertSpec.BookRenderer renderer) {}
    private volatile List<Entry> entries = List.of();

    public void replace(List<RunbookCandidate> candidates) {
        var compiled = new ArrayList<Entry>();
        for (var candidate : candidates) {
            var book = candidate.runbook();
            try {
                var predicate = RuleIndex.compileMatch(matchTree(candidate.matches()));
                compiled.add(new Entry(book, predicate, AlertSpec.compileBook(book)));
            } catch (IllegalArgumentException invalid) {
                log.warn("Skipping invalid auto runbook {}", book.id());
            }
        }
        compiled.sort(Comparator.comparing(entry -> entry.book().name(),
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())));
        entries = List.copyOf(compiled);
    }

    // The initial SQL schema also documents an object of path -> expected value.
    private static JsonNode matchTree(JsonNode matches) {
        if (matches == null || !matches.isObject() || matches.isEmpty()
                || matches.has("path") || matches.has("all") || matches.has("any") || matches.has("not")) return matches;
        var root = JsonNodeFactory.instance.objectNode();
        var all = root.putArray("all");
        matches.fields().forEachRemaining(field ->
                all.addObject().put("path", field.getKey()).put("op", "eq").set("value", field.getValue()));
        return root;
    }

    public Runbook select(Rule rule, JsonNode event) {
        if (!rule.alert().autoRunbook()) return rule.alert().runbook().apply(event);
        for (var entry : entries) {
            if (entry.matches().test(event)) {
                return entry.renderer().render(event, rule.id(), rule.definition().name());
            }
        }
        return null;
    }
}
