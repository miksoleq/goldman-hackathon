package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A validated rule path. Parsing is done once; resolution only walks the
 * precompiled segments and streams non-null values to the caller.
 */
public final class CompiledPath {

    private static final byte PROPERTY = 0;
    private static final byte ARRAY_INDEX = 1;
    private static final byte ARRAY_WILDCARD = 2;
    private static final Predicate<JsonNode> ANY_VALUE = ignored -> true;

    private final String source;
    private final String[] segments;
    private final byte[] segmentKinds;
    private final int[] arrayIndexes;

    private CompiledPath(String source, String[] segments, byte[] segmentKinds, int[] arrayIndexes) {
        this.source = source;
        this.segments = segments;
        this.segmentKinds = segmentKinds;
        this.arrayIndexes = arrayIndexes;
    }

    public static CompiledPath compile(String path) {
        if (path == null || path.isEmpty()) {
            throw new InvalidRuleDefinitionException("Rule path must not be empty");
        }
        if (path.length() > 300) {
            throw new InvalidRuleDefinitionException("Rule path exceeds 300 characters");
        }

        List<String> segments = new ArrayList<>();
        List<Byte> kinds = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        int cursor = readRoot(path, segments, kinds, indexes);

        while (cursor < path.length()) {
            char marker = path.charAt(cursor);
            if (marker == '.') {
                cursor = readDotProperty(path, cursor + 1, segments, kinds, indexes);
            } else if (marker == '[') {
                cursor = readBracketSegment(path, cursor, segments, kinds, indexes);
            } else {
                throw invalidPath(path);
            }
        }

        String[] segmentArray = segments.toArray(String[]::new);
        byte[] kindArray = new byte[kinds.size()];
        int[] indexArray = new int[indexes.size()];
        for (int i = 0; i < kinds.size(); i++) {
            kindArray[i] = kinds.get(i);
            indexArray[i] = indexes.get(i);
        }
        return new CompiledPath(path, segmentArray, kindArray, indexArray);
    }

    /** Returns a copy so callers cannot mutate this compiled path. */
    public String[] segments() {
        return segments.clone();
    }

    public String source() {
        return source;
    }

    /** Returns the first resolved non-null value, or {@code null} if none exists. */
    public JsonNode get(JsonNode event) {
        return firstValue(event, 0);
    }

    /**
     * Applies {@code valuePredicate} to resolved values and stops at the first
     * match. Missing fields, nulls, and shape mismatches resolve to no value.
     */
    public boolean anyMatch(JsonNode event, Predicate<JsonNode> valuePredicate) {
        Objects.requireNonNull(valuePredicate, "valuePredicate");
        return anyMatch(event, 0, valuePredicate);
    }

    /** True if at least one non-null value can be resolved. */
    public boolean exists(JsonNode event) {
        return anyMatch(event, ANY_VALUE);
    }

    /**
     * Visits each resolved non-null value without building an intermediate
     * list. The visitor is supplied by the caller and can be reused.
     */
    public void forEachValue(JsonNode event, ValueConsumer visitor) {
        Objects.requireNonNull(visitor, "visitor");
        visitValues(event, 0, visitor);
    }

    @FunctionalInterface
    public interface ValueConsumer {
        void accept(JsonNode value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CompiledPath that && source.equals(that.source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }

    @Override
    public String toString() {
        return source;
    }

    private static int readRoot(
            String path,
            List<String> segments,
            List<Byte> kinds,
            List<Integer> indexes
    ) {
        int end = 0;
        while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
            end++;
        }
        String root = path.substring(0, end);
        if (!root.equals("payload") && !root.equals("meta")) {
            throw invalidPath(path);
        }
        addProperty(root, segments, kinds, indexes);
        return end;
    }

    private static int readDotProperty(
            String path,
            int cursor,
            List<String> segments,
            List<Byte> kinds,
            List<Integer> indexes
    ) {
        int start = cursor;
        while (cursor < path.length() && path.charAt(cursor) != '.' && path.charAt(cursor) != '[') {
            char character = path.charAt(cursor);
            if (character == ']' || character == '\'' || Character.isWhitespace(character)) {
                throw invalidPath(path);
            }
            cursor++;
        }
        if (cursor == start) {
            throw invalidPath(path);
        }
        addProperty(path.substring(start, cursor), segments, kinds, indexes);
        return cursor;
    }

    private static int readBracketSegment(
            String path,
            int cursor,
            List<String> segments,
            List<Byte> kinds,
            List<Integer> indexes
    ) {
        cursor++;
        if (cursor >= path.length()) {
            throw invalidPath(path);
        }

        char first = path.charAt(cursor);
        if (first == '*') {
            if (cursor + 1 >= path.length() || path.charAt(cursor + 1) != ']') {
                throw invalidPath(path);
            }
            segments.add("*");
            kinds.add(ARRAY_WILDCARD);
            indexes.add(-1);
            return cursor + 2;
        }

        if (first == '\'') {
            int keyStart = cursor + 1;
            int keyEnd = path.indexOf('\'', keyStart);
            if (keyEnd < 0 || keyEnd == keyStart || keyEnd + 1 >= path.length()
                    || path.charAt(keyEnd + 1) != ']') {
                throw invalidPath(path);
            }
            addProperty(path.substring(keyStart, keyEnd), segments, kinds, indexes);
            return keyEnd + 2;
        }

        int index = 0;
        int digitCount = 0;
        while (cursor < path.length() && path.charAt(cursor) != ']') {
            char digit = path.charAt(cursor);
            if (digit < '0' || digit > '9') {
                throw invalidPath(path);
            }
            int numericDigit = digit - '0';
            if (index > (Integer.MAX_VALUE - numericDigit) / 10) {
                throw invalidPath(path);
            }
            index = index * 10 + numericDigit;
            digitCount++;
            cursor++;
        }
        if (digitCount == 0 || cursor >= path.length()) {
            throw invalidPath(path);
        }
        segments.add(path.substring(cursor - digitCount, cursor));
        kinds.add(ARRAY_INDEX);
        indexes.add(index);
        return cursor + 1;
    }

    private static void addProperty(
            String property,
            List<String> segments,
            List<Byte> kinds,
            List<Integer> indexes
    ) {
        segments.add(property);
        kinds.add(PROPERTY);
        indexes.add(-1);
    }

    private JsonNode firstValue(JsonNode node, int position) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (position == segmentKinds.length) {
            return node;
        }
        return switch (segmentKinds[position]) {
            case PROPERTY -> node.isObject()
                    ? firstValue(node.get(segments[position]), position + 1)
                    : null;
            case ARRAY_INDEX -> node.isArray() && arrayIndexes[position] < node.size()
                    ? firstValue(node.get(arrayIndexes[position]), position + 1)
                    : null;
            case ARRAY_WILDCARD -> firstArrayValue(node, position);
            default -> null;
        };
    }

    private JsonNode firstArrayValue(JsonNode node, int position) {
        if (!node.isArray()) {
            return null;
        }
        for (JsonNode element : node) {
            JsonNode value = firstValue(element, position + 1);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean anyMatch(JsonNode node, int position, Predicate<JsonNode> valuePredicate) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (position == segmentKinds.length) {
            return valuePredicate.test(node);
        }
        return switch (segmentKinds[position]) {
            case PROPERTY -> node.isObject()
                    && anyMatch(node.get(segments[position]), position + 1, valuePredicate);
            case ARRAY_INDEX -> node.isArray() && arrayIndexes[position] < node.size()
                    && anyMatch(node.get(arrayIndexes[position]), position + 1, valuePredicate);
            case ARRAY_WILDCARD -> anyArrayMatch(node, position, valuePredicate);
            default -> false;
        };
    }

    private boolean anyArrayMatch(JsonNode node, int position, Predicate<JsonNode> valuePredicate) {
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode element : node) {
            if (anyMatch(element, position + 1, valuePredicate)) {
                return true;
            }
        }
        return false;
    }

    private void visitValues(JsonNode node, int position, ValueConsumer visitor) {
        if (node == null || node.isNull()) {
            return;
        }
        if (position == segmentKinds.length) {
            visitor.accept(node);
            return;
        }
        switch (segmentKinds[position]) {
            case PROPERTY -> {
                if (node.isObject()) {
                    visitValues(node.get(segments[position]), position + 1, visitor);
                }
            }
            case ARRAY_INDEX -> {
                if (node.isArray() && arrayIndexes[position] < node.size()) {
                    visitValues(node.get(arrayIndexes[position]), position + 1, visitor);
                }
            }
            case ARRAY_WILDCARD -> {
                if (node.isArray()) {
                    for (JsonNode element : node) {
                        visitValues(element, position + 1, visitor);
                    }
                }
            }
            default -> {
                // Segment kinds are produced only by the parser above.
            }
        }
    }

    private static InvalidRuleDefinitionException invalidPath(String path) {
        return new InvalidRuleDefinitionException("Invalid rule path: " + path);
    }
}
