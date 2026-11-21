package com.goldman.alerting.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.re2j.Pattern;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.util.List;

/** Formal schema and semantic checks shared by the YAML writer and snapshot reader. */
public final class RuleSyntax {
    private static final JsonSchema SCHEMA = loadSchema();

    private RuleSyntax() {}

    private static JsonSchema loadSchema() {
        try (var input = RuleSyntax.class.getResourceAsStream("/schemas/rule-definition.v1.schema.json")) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(new ObjectMapper().readTree(input));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Condition parse(JsonNode definition) {
        if (definition == null || !definition.isObject()) throw new InvalidRuleDefinitionException("Expected a rule definition");
        var version = definition.path("v");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
            throw new InvalidRuleDefinitionException("Unsupported or missing rule-definition version");
        }
        // Bound recursion before evaluating the recursive schema.
        var condition = ConditionParser.parse(definition.path("match"));
        var errors = SCHEMA.validate(definition);
        if (!errors.isEmpty()) throw new InvalidRuleDefinitionException(errors.iterator().next().getMessage());
        for (var path : definition.path("dedup").path("keys")) CompiledPath.compile(path.textValue());
        return condition;
    }

    public static void validateForSave(JsonNode definition) {
        validatePatterns(parse(definition));
        for (String key : List.of("title", "body", "severity")) {
            if (definition.has(key)) RuleTemplate.compile(definition.get(key).textValue(), "", "");
        }
    }

    private static void validatePatterns(Condition condition) {
        if (condition instanceof Condition.All all) all.conditions().forEach(RuleSyntax::validatePatterns);
        else if (condition instanceof Condition.Any any) any.conditions().forEach(RuleSyntax::validatePatterns);
        else if (condition instanceof Condition.Not not) validatePatterns(not.condition());
        else if (condition instanceof Condition.Leaf leaf && leaf.operator() == Condition.Operator.REGEX) {
            try {
                Pattern.compile(leaf.value().textValue());
            } catch (com.google.re2j.PatternSyntaxException error) {
                throw new InvalidRuleDefinitionException("Invalid RE2 regex at " + leaf.path().source());
            }
        }
    }
}
