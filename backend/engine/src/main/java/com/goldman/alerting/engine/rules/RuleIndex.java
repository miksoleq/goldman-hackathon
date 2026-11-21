package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Immutable inverted-index snapshot for schema-less JSON rule matching. */
public final class RuleIndex {

    private static final Set<String> LOW_CARDINALITY_FIELDS = Set.of("env", "environment", "severity", "level");
    private static final String IGNORE_CASE_KEY_PREFIX = "\u0001ignore-case:";

    private final Rule[] rulesByOrdinal;
    private final Map<CompiledPath, Map<String, Rule[]>> byEquality;
    private final Set<CompiledPath> caseInsensitivePaths;
    private final Rule[] alwaysCheck;
    private final List<RuleDefinition> definitions;
    private final Map<CompiledPath, Set<String>> literalValuesByPath;

    private RuleIndex(
            Rule[] rulesByOrdinal,
            Map<CompiledPath, Map<String, Rule[]>> byEquality,
            Rule[] alwaysCheck,
            List<RuleDefinition> definitions,
            Map<CompiledPath, Set<String>> literalValuesByPath
    ) {
        this.rulesByOrdinal = rulesByOrdinal;
        this.byEquality = byEquality;
        this.caseInsensitivePaths = findCaseInsensitivePaths(byEquality);
        this.alwaysCheck = alwaysCheck;
        this.definitions = List.copyOf(definitions);
        this.literalValuesByPath = immutableLiteralValues(literalValuesByPath);
    }

    /** Compiles all rules into one immutable snapshot. Input order defines ordinals. */
    public static RuleIndex compile(List<RuleDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        List<RuleDefinition> safeDefinitions = List.copyOf(definitions);
        Map<CompiledPath, Set<String>> literalValues = collectLiteralValues(safeDefinitions);
        Map<CompiledPath, Map<String, Map<Integer, Rule>>> mutableIndex = new LinkedHashMap<>();
        List<Rule> unindexedRules = new ArrayList<>();
        Rule[] rules = new Rule[safeDefinitions.size()];

        for (int ordinal = 0; ordinal < safeDefinitions.size(); ordinal++) {
            RuleDefinition definition = Objects.requireNonNull(safeDefinitions.get(ordinal), "rule definition");
            validateCondition(definition.condition());
            Rule rule = compileRule(ordinal, definition);
            rules[ordinal] = rule;
            List<IndexCondition> indexConditions = indexConditions(definition.condition(), literalValues);
            if (indexConditions.isEmpty()) {
                unindexedRules.add(rule);
            } else {
                addToIndex(mutableIndex, rule, indexConditions);
            }
        }

        return new RuleIndex(
                rules,
                freezeIndex(mutableIndex),
                unindexedRules.toArray(Rule[]::new),
                safeDefinitions,
                literalValues
        );
    }

    /**
     * Returns a new snapshot containing {@code definition}. Existing ids are
     * replaced; a new id gets an appended ordinal and only its buckets are
     * copied into the new index. Existing snapshots remain unchanged.
     */
    public RuleIndex withRule(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        int existingOrdinal = findOrdinal(definition.id());
        if (existingOrdinal >= 0) {
            List<RuleDefinition> replaced = new ArrayList<>(definitions);
            replaced.set(existingOrdinal, definition);
            return compile(replaced);
        }

        validateCondition(definition.condition());
        Map<CompiledPath, Set<String>> updatedLiteralValues = addLiteralValues(
                literalValuesByPath,
                definition.condition()
        );
        Rule rule = compileRule(rulesByOrdinal.length, definition);
        List<IndexCondition> indexConditions = indexConditions(definition.condition(), updatedLiteralValues);
        Rule[] updatedAlwaysCheck = alwaysCheck;
        Map<CompiledPath, Map<String, Rule[]>> updatedIndex = byEquality;
        if (indexConditions.isEmpty()) {
            updatedAlwaysCheck = Arrays.copyOf(alwaysCheck, alwaysCheck.length + 1);
            updatedAlwaysCheck[alwaysCheck.length] = rule;
        } else {
            updatedIndex = appendToIndex(byEquality, rule, indexConditions);
        }

        Rule[] updatedRules = Arrays.copyOf(rulesByOrdinal, rulesByOrdinal.length + 1);
        updatedRules[rulesByOrdinal.length] = rule;
        List<RuleDefinition> updatedDefinitions = new ArrayList<>(definitions);
        updatedDefinitions.add(definition);
        return new RuleIndex(
                updatedRules,
                updatedIndex,
                updatedAlwaysCheck,
                updatedDefinitions,
                updatedLiteralValues
        );
    }

    /** Returns the full matches and how many unique rules were evaluated. */
    public MatchResult matchWithDiagnostics(JsonNode event) {
        BitSet candidates = new BitSet(rulesByOrdinal.length);
        CandidateCollector collector = new CandidateCollector(candidates);
        for (Map.Entry<CompiledPath, Map<String, Rule[]>> pathEntry : byEquality.entrySet()) {
            collector.use(pathEntry.getValue(), caseInsensitivePaths.contains(pathEntry.getKey()));
            pathEntry.getKey().forEachValue(event, collector);
        }
        for (Rule rule : alwaysCheck) {
            candidates.set(rule.ordinal());
        }

        int candidateCount = candidates.cardinality();
        List<Rule> matches = new ArrayList<>();
        List<String> evaluationErrors = new ArrayList<>();
        for (int ordinal = candidates.nextSetBit(0); ordinal >= 0; ordinal = candidates.nextSetBit(ordinal + 1)) {
            Rule rule = rulesByOrdinal[ordinal];
            try {
                if (rule.predicate().test(event)) {
                    matches.add(rule);
                }
            } catch (RuntimeException ignored) {
                // A malformed or unexpected value must not stop evaluation of other rules.
                evaluationErrors.add(rule.id());
            }
        }
        return new MatchResult(matches, candidateCount, evaluationErrors);
    }

    public List<Rule> match(JsonNode event) {
        return matchWithDiagnostics(event).matchedRules();
    }

    public int ruleCount() {
        return rulesByOrdinal.length;
    }

    public int alwaysCheckCount() {
        return alwaysCheck.length;
    }

    public List<RuleDefinition> definitions() {
        return definitions;
    }

    public record MatchResult(
            List<Rule> matchedRules,
            int candidateCount,
            List<String> evaluationErrorRuleIds
    ) {
        public MatchResult {
            matchedRules = List.copyOf(matchedRules);
            evaluationErrorRuleIds = List.copyOf(evaluationErrorRuleIds);
        }

        public int evaluationErrorCount() {
            return evaluationErrorRuleIds.size();
        }
    }

    private static Rule compileRule(int ordinal, RuleDefinition definition) {
        return new Rule(ordinal, definition.id(), definition.condition(), compilePredicate(definition.condition()), definition.alert());
    }

    private static Predicate<JsonNode> compilePredicate(Condition condition) {
        if (condition instanceof Condition.All all) {
            List<Predicate<JsonNode>> children = compileChildren(all.conditions());
            return event -> {
                for (int i = 0; i < children.size(); i++) {
                    if (!children.get(i).test(event)) {
                        return false;
                    }
                }
                return true;
            };
        }
        if (condition instanceof Condition.Any any) {
            List<Predicate<JsonNode>> children = compileChildren(any.conditions());
            return event -> {
                for (int i = 0; i < children.size(); i++) {
                    if (children.get(i).test(event)) {
                        return true;
                    }
                }
                return false;
            };
        }
        if (condition instanceof Condition.Not not) {
            Predicate<JsonNode> child = compilePredicate(not.condition());
            return event -> !child.test(event);
        }
        return compileLeaf((Condition.Leaf) condition);
    }

    private static List<Predicate<JsonNode>> compileChildren(List<Condition> conditions) {
        List<Predicate<JsonNode>> compiled = new ArrayList<>(conditions.size());
        for (Condition child : conditions) {
            compiled.add(compilePredicate(child));
        }
        return List.copyOf(compiled);
    }

    private static Predicate<JsonNode> compileLeaf(Condition.Leaf leaf) {
        CompiledPath path = leaf.path();
        Condition.Operator operator = leaf.operator();
        JsonNode value = leaf.value();
        boolean ignoreCase = leaf.ignoreCase();

        if (operator == Condition.Operator.JSON_SCHEMA) {
            Predicate<JsonNode> matchesShape = com.goldman.alerting.schema.EventShape.compile(value);
            return event -> path.anyMatch(event, matchesShape);
        }
        if (operator == Condition.Operator.EXISTS) {
            boolean expectedToExist = value.booleanValue();
            return event -> path.exists(event) == expectedToExist;
        }
        if (operator == Condition.Operator.EQ || operator == Condition.Operator.NEQ) {
            Predicate<JsonNode> equals = actual -> jsonEquals(actual, value, ignoreCase);
            return operator == Condition.Operator.EQ
                    ? event -> path.anyMatch(event, equals)
                    : event -> path.anyMatch(event, actual -> !equals.test(actual));
        }
        if (operator == Condition.Operator.IN || operator == Condition.Operator.NIN) {
            JsonNode[] expectedValues = arrayValues(value);
            Predicate<JsonNode> contained = actual -> containsJsonValue(expectedValues, actual, ignoreCase);
            return operator == Condition.Operator.IN
                    ? event -> path.anyMatch(event, contained)
                    : event -> path.anyMatch(event, actual -> !contained.test(actual));
        }
        if (operator == Condition.Operator.GT || operator == Condition.Operator.GTE
                || operator == Condition.Operator.LT || operator == Condition.Operator.LTE) {
            BigDecimal expected = value.decimalValue();
            return event -> path.anyMatch(event, actual -> compareNumber(actual, expected, operator));
        }
        if (operator == Condition.Operator.CONTAINS) {
            String expectedText = scalarText(value);
            return event -> path.anyMatch(event, actual -> contains(actual, value, expectedText, ignoreCase));
        }
        if (operator == Condition.Operator.STARTS_WITH || operator == Condition.Operator.ENDS_WITH) {
            String expectedText = value.textValue();
            return event -> path.anyMatch(event, actual -> {
                String actualText = scalarText(actual);
                if (actualText == null) {
                    return false;
                }
                return operator == Condition.Operator.STARTS_WITH
                        ? startsWith(actualText, expectedText, ignoreCase)
                        : endsWith(actualText, expectedText, ignoreCase);
            });
        }
        if (operator == Condition.Operator.REGEX) {
            Pattern pattern;
            try {
                pattern = Pattern.compile(value.textValue(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
            } catch (RuntimeException exception) {
                throw new InvalidRuleDefinitionException("Invalid RE2/J regular expression");
            }
            return event -> path.anyMatch(event, actual -> {
                String actualText = scalarText(actual);
                return actualText != null && pattern.matcher(actualText).find();
            });
        }
        throw new InvalidRuleDefinitionException("Unsupported rule operator: " + operator);
    }

    private static JsonNode[] arrayValues(JsonNode array) {
        JsonNode[] values = new JsonNode[array.size()];
        for (int i = 0; i < array.size(); i++) {
            values[i] = array.get(i);
        }
        return values;
    }

    private static boolean compareNumber(JsonNode actual, BigDecimal expected, Condition.Operator operator) {
        BigDecimal actualNumber = numberValue(actual);
        if (actualNumber == null) {
            return false;
        }
        int comparison = actualNumber.compareTo(expected);
        return switch (operator) {
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            default -> false;
        };
    }

    private static BigDecimal numberValue(JsonNode node) {
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.textValue());
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static boolean contains(JsonNode actual, JsonNode expected, String expectedText, boolean ignoreCase) {
        if (actual.isArray()) {
            for (JsonNode element : actual) {
                if (jsonEquals(element, expected, ignoreCase)) {
                    return true;
                }
            }
            return false;
        }
        String actualText = scalarText(actual);
        return actualText != null && expectedText != null && containsText(actualText, expectedText, ignoreCase);
    }

    private static boolean containsJsonValue(JsonNode[] expectedValues, JsonNode actual, boolean ignoreCase) {
        for (JsonNode expected : expectedValues) {
            if (jsonEquals(actual, expected, ignoreCase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean jsonEquals(JsonNode left, JsonNode right, boolean ignoreCase) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.isNull() || right.isNull()) {
            return left.isNull() && right.isNull();
        }
        if (left.isNumber() || right.isNumber()) {
            return left.isNumber() && right.isNumber()
                    && left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isTextual() || right.isTextual()) {
            if (!left.isTextual() || !right.isTextual()) {
                return false;
            }
            return ignoreCase
                    ? left.textValue().equalsIgnoreCase(right.textValue())
                    : left.textValue().equals(right.textValue());
        }
        if (left.isBoolean() || right.isBoolean()) {
            return left.isBoolean() && right.isBoolean() && left.booleanValue() == right.booleanValue();
        }
        if (left.isArray() || right.isArray()) {
            if (!left.isArray() || !right.isArray() || left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!jsonEquals(left.get(i), right.get(i), ignoreCase)) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() || right.isObject()) {
            if (!left.isObject() || !right.isObject() || left.size() != right.size()) {
                return false;
            }
            var fields = left.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!jsonEquals(field.getValue(), right.get(field.getKey()), ignoreCase)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String scalarText(JsonNode node) {
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }

    private static boolean containsText(String value, String expected, boolean ignoreCase) {
        if (expected.isEmpty()) {
            return true;
        }
        for (int start = 0; start <= value.length() - expected.length(); start++) {
            if (value.regionMatches(ignoreCase, start, expected, 0, expected.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(String value, String expected, boolean ignoreCase) {
        return value.length() >= expected.length()
                && value.regionMatches(ignoreCase, 0, expected, 0, expected.length());
    }

    private static boolean endsWith(String value, String expected, boolean ignoreCase) {
        int start = value.length() - expected.length();
        return start >= 0 && value.regionMatches(ignoreCase, start, expected, 0, expected.length());
    }

    private static List<IndexCondition> indexConditions(
            Condition root,
            Map<CompiledPath, Set<String>> literalValues
    ) {
        if (root instanceof Condition.Leaf leaf) {
            IndexCondition candidate = indexable(leaf);
            return candidate == null ? List.of() : List.of(candidate);
        }
        if (root instanceof Condition.All all) {
            IndexCondition best = bestDirectCondition(all.conditions(), literalValues);
            return best == null ? List.of() : List.of(best);
        }
        if (root instanceof Condition.Any any) {
            List<IndexCondition> result = new ArrayList<>();
            for (Condition branch : any.conditions()) {
                List<IndexCondition> branchConditions = guaranteedBranchConditions(branch, literalValues);
                if (branchConditions == null || branchConditions.isEmpty()) {
                    return List.of();
                }
                result.addAll(branchConditions);
            }
            return distinctConditions(result);
        }
        return List.of();
    }

    /** Returns null when a branch cannot be proven to yield an indexed candidate. */
    private static List<IndexCondition> guaranteedBranchConditions(
            Condition branch,
            Map<CompiledPath, Set<String>> literalValues
    ) {
        if (branch instanceof Condition.Leaf leaf) {
            IndexCondition candidate = indexable(leaf);
            return candidate == null ? null : List.of(candidate);
        }
        if (branch instanceof Condition.All all) {
            IndexCondition best = bestDirectCondition(all.conditions(), literalValues);
            return best == null ? null : List.of(best);
        }
        if (branch instanceof Condition.Any any) {
            List<IndexCondition> result = new ArrayList<>();
            for (Condition child : any.conditions()) {
                List<IndexCondition> childConditions = guaranteedBranchConditions(child, literalValues);
                if (childConditions == null || childConditions.isEmpty()) {
                    return null;
                }
                result.addAll(childConditions);
            }
            return distinctConditions(result);
        }
        return null;
    }

    private static IndexCondition bestDirectCondition(
            List<Condition> conditions,
            Map<CompiledPath, Set<String>> literalValues
    ) {
        IndexCondition best = null;
        for (Condition child : conditions) {
            if (child instanceof Condition.Leaf leaf) {
                IndexCondition candidate = indexable(leaf);
                if (candidate != null && (best == null || isBetter(candidate, best, literalValues))) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean isBetter(
            IndexCondition candidate,
            IndexCondition current,
            Map<CompiledPath, Set<String>> literalValues
    ) {
        boolean candidateEquals = candidate.operator() == Condition.Operator.EQ;
        boolean currentEquals = current.operator() == Condition.Operator.EQ;
        if (candidateEquals != currentEquals) {
            return candidateEquals;
        }
        int candidateCardinality = literalValues.getOrDefault(candidate.path(), Set.of()).size();
        int currentCardinality = literalValues.getOrDefault(current.path(), Set.of()).size();
        if (candidateCardinality != currentCardinality) {
            return candidateCardinality > currentCardinality;
        }
        boolean candidateLowCardinality = isLowCardinality(candidate.path());
        boolean currentLowCardinality = isLowCardinality(current.path());
        if (candidateLowCardinality != currentLowCardinality) {
            return !candidateLowCardinality;
        }
        return candidate.path().source().compareTo(current.path().source()) < 0;
    }

    private static boolean isLowCardinality(CompiledPath path) {
        String[] segments = path.segments();
        return LOW_CARDINALITY_FIELDS.contains(segments[segments.length - 1].toLowerCase(java.util.Locale.ROOT));
    }

    private static IndexCondition indexable(Condition.Leaf leaf) {
        if (leaf.operator() == Condition.Operator.EQ) {
            JsonNode value = leaf.value();
            return value.isValueNode()
                    ? new IndexCondition(leaf.path(), leaf.operator(), List.of(indexKey(value, leaf.ignoreCase())))
                    : null;
        }
        if (leaf.operator() == Condition.Operator.IN) {
            JsonNode array = leaf.value();
            List<String> values = new ArrayList<>(array.size());
            for (JsonNode value : array) {
                if (!value.isValueNode()) {
                    return null;
                }
                values.add(indexKey(value, leaf.ignoreCase()));
            }
            return new IndexCondition(leaf.path(), leaf.operator(), List.copyOf(new LinkedHashSet<>(values)));
        }
        return null;
    }

    private static List<IndexCondition> distinctConditions(List<IndexCondition> conditions) {
        Map<IndexKey, IndexCondition> distinct = new LinkedHashMap<>();
        for (IndexCondition condition : conditions) {
            for (String value : condition.values()) {
                IndexKey key = new IndexKey(condition.path(), value);
                distinct.putIfAbsent(key, new IndexCondition(condition.path(), condition.operator(), List.of(value)));
            }
        }
        return List.copyOf(distinct.values());
    }

    private static void addToIndex(
            Map<CompiledPath, Map<String, Map<Integer, Rule>>> mutableIndex,
            Rule rule,
            List<IndexCondition> conditions
    ) {
        for (IndexCondition condition : conditions) {
            Map<String, Map<Integer, Rule>> values = mutableIndex.computeIfAbsent(
                    condition.path(),
                    ignored -> new LinkedHashMap<>()
            );
            for (String value : condition.values()) {
                values.computeIfAbsent(value, ignored -> new LinkedHashMap<>()).put(rule.ordinal(), rule);
            }
        }
    }

    private static Map<CompiledPath, Map<String, Rule[]>> freezeIndex(
            Map<CompiledPath, Map<String, Map<Integer, Rule>>> mutableIndex
    ) {
        Map<CompiledPath, Map<String, Rule[]>> frozen = new LinkedHashMap<>();
        mutableIndex.forEach((path, values) -> {
            Map<String, Rule[]> frozenValues = new LinkedHashMap<>();
            values.forEach((value, rules) -> frozenValues.put(value, rules.values().toArray(Rule[]::new)));
            frozen.put(path, Collections.unmodifiableMap(frozenValues));
        });
        return Collections.unmodifiableMap(frozen);
    }

    private static Map<CompiledPath, Map<String, Rule[]>> appendToIndex(
            Map<CompiledPath, Map<String, Rule[]>> oldIndex,
            Rule rule,
            List<IndexCondition> conditions
    ) {
        Map<CompiledPath, Map<String, Rule[]>> updated = new LinkedHashMap<>();
        oldIndex.forEach((path, values) -> updated.put(path, new LinkedHashMap<>(values)));
        Set<IndexKey> added = new HashSet<>();
        for (IndexCondition condition : conditions) {
            Map<String, Rule[]> values = updated.computeIfAbsent(condition.path(), ignored -> new LinkedHashMap<>());
            for (String value : condition.values()) {
                if (!added.add(new IndexKey(condition.path(), value))) {
                    continue;
                }
                Rule[] existing = values.get(value);
                if (existing == null) {
                    values.put(value, new Rule[]{rule});
                } else {
                    Rule[] appended = Arrays.copyOf(existing, existing.length + 1);
                    appended[existing.length] = rule;
                    values.put(value, appended);
                }
            }
        }
        return freezeArraysAndMaps(updated);
    }

    private static Map<CompiledPath, Map<String, Rule[]>> freezeArraysAndMaps(
            Map<CompiledPath, Map<String, Rule[]>> mutable
    ) {
        Map<CompiledPath, Map<String, Rule[]>> frozen = new LinkedHashMap<>();
        mutable.forEach((path, values) -> frozen.put(path, Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        return Collections.unmodifiableMap(frozen);
    }

    private static Map<CompiledPath, Set<String>> collectLiteralValues(List<RuleDefinition> definitions) {
        Map<CompiledPath, Set<String>> values = new HashMap<>();
        for (RuleDefinition definition : definitions) {
            collectLiteralValues(definition.condition(), values);
        }
        return values;
    }

    private static Map<CompiledPath, Set<String>> addLiteralValues(
            Map<CompiledPath, Set<String>> existing,
            Condition condition
    ) {
        Map<CompiledPath, Set<String>> combined = new HashMap<>();
        existing.forEach((path, values) -> combined.put(path, new HashSet<>(values)));
        collectLiteralValues(condition, combined);
        return combined;
    }

    private static Map<CompiledPath, Set<String>> immutableLiteralValues(Map<CompiledPath, Set<String>> values) {
        Map<CompiledPath, Set<String>> immutable = new HashMap<>();
        values.forEach((path, pathValues) -> immutable.put(path, Set.copyOf(pathValues)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Set<CompiledPath> findCaseInsensitivePaths(Map<CompiledPath, Map<String, Rule[]>> index) {
        Set<CompiledPath> paths = new HashSet<>();
        index.forEach((path, values) -> {
            if (values.keySet().stream().anyMatch(key -> key.startsWith(IGNORE_CASE_KEY_PREFIX))) {
                paths.add(path);
            }
        });
        return Set.copyOf(paths);
    }

    private static String indexKey(JsonNode value, boolean ignoreCase) {
        if (ignoreCase && value.isTextual()) {
            return IGNORE_CASE_KEY_PREFIX + value.textValue().toLowerCase(java.util.Locale.ROOT);
        }
        return value.asText();
    }

    private static void collectLiteralValues(Condition condition, Map<CompiledPath, Set<String>> values) {
        if (condition instanceof Condition.Leaf leaf) {
            if (leaf.operator() == Condition.Operator.EQ) {
                JsonNode value = leaf.value();
                if (value.isValueNode()) {
                    values.computeIfAbsent(leaf.path(), ignored -> new HashSet<>()).add(value.asText());
                }
            } else if (leaf.operator() == Condition.Operator.IN) {
                JsonNode array = leaf.value();
                for (JsonNode value : array) {
                    if (value.isValueNode()) {
                        values.computeIfAbsent(leaf.path(), ignored -> new HashSet<>()).add(value.asText());
                    }
                }
            }
            return;
        }
        if (condition instanceof Condition.All all) {
            all.conditions().forEach(child -> collectLiteralValues(child, values));
        } else if (condition instanceof Condition.Any any) {
            any.conditions().forEach(child -> collectLiteralValues(child, values));
        } else if (condition instanceof Condition.Not not) {
            collectLiteralValues(not.condition(), values);
        }
    }

    private static void validateCondition(Condition root) {
        validateCondition(root, 1, new int[]{0});
    }

    private static void validateCondition(Condition condition, int depth, int[] nodeCount) {
        if (depth > 10) {
            throw new InvalidRuleDefinitionException("Condition tree exceeds depth 10");
        }
        if (++nodeCount[0] > 100) {
            throw new InvalidRuleDefinitionException("Condition tree exceeds 100 nodes");
        }
        if (condition instanceof Condition.All all) {
            all.conditions().forEach(child -> validateCondition(child, depth + 1, nodeCount));
        } else if (condition instanceof Condition.Any any) {
            any.conditions().forEach(child -> validateCondition(child, depth + 1, nodeCount));
        } else if (condition instanceof Condition.Not not) {
            validateCondition(not.condition(), depth + 1, nodeCount);
        }
    }

    private int findOrdinal(String id) {
        for (int i = 0; i < rulesByOrdinal.length; i++) {
            if (rulesByOrdinal[i].id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private record IndexCondition(CompiledPath path, Condition.Operator operator, List<String> values) {
        private IndexCondition {
            values = List.copyOf(values);
        }
    }

    private record IndexKey(CompiledPath path, String value) {
    }

    private static final class CandidateCollector implements CompiledPath.ValueConsumer {
        private final BitSet candidates;
        private Map<String, Rule[]> currentValues;
        private boolean hasIgnoreCaseValues;

        private CandidateCollector(BitSet candidates) {
            this.candidates = candidates;
        }

        private void use(Map<String, Rule[]> values, boolean hasIgnoreCaseValues) {
            currentValues = values;
            this.hasIgnoreCaseValues = hasIgnoreCaseValues;
        }

        @Override
        public void accept(JsonNode value) {
            if (!value.isValueNode()) {
                return;
            }
            addCandidates(currentValues.get(value.asText()));
            if (hasIgnoreCaseValues && value.isTextual()) {
                String insensitiveKey = IGNORE_CASE_KEY_PREFIX
                        + value.textValue().toLowerCase(java.util.Locale.ROOT);
                addCandidates(currentValues.get(insensitiveKey));
            }
        }

        private void addCandidates(Rule[] rules) {
            if (rules == null) {
                return;
            }
            for (Rule rule : rules) {
                candidates.set(rule.ordinal());
            }
        }
    }
}
