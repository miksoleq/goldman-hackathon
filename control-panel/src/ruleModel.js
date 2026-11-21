export const OPERATORS = [
  ["exists", "Field is present", "boolean"],
  ["eq", "Is equal to", "typed"],
  ["ne", "Is not equal to", "typed"],
  ["in", "Is one of", "list"],
  ["nin", "Is not one of", "list"],
  ["gt", "Is greater than", "number"],
  ["gte", "Is at least", "number"],
  ["lt", "Is less than", "number"],
  ["lte", "Is at most", "number"],
  ["contains", "Contains", "typed"],
  ["startsWith", "Starts with", "text"],
  ["endsWith", "Ends with", "text"],
  ["regex", "Matches regular expression (RE2)", "regex"],
  ["jsonSchema", "Matches JSON structure", "schema"]
];

export const ROOT_META_FIELDS = ["source", "adapter", "eventId", "receivedAt", "contentType"];

export function createLeaf() {
  return { kind: "condition", scope: "payload", path: "", op: "eq", valueType: "string", value: "" };
}

export function createGroup() {
  return { kind: "group", operator: "all", children: [createLeaf()] };
}

export function createSchema(type = "object") {
  return {
    type,
    properties: [],
    required: [],
    additionalProperties: false,
    ...(type === "array" ? { items: createSchema("string") } : {})
  };
}

function valueOf(condition) {
  if (condition.op === "exists") return condition.value === "true";
  if (condition.op === "jsonSchema") return condition.schema;
  if (condition.op === "in" || condition.op === "nin") {
    return condition.value.split(",").map(value => value.trim()).filter(Boolean)
      .map(value => castValue(value, condition.valueType));
  }
  return castValue(condition.value, condition.valueType);
}

function castValue(value, type) {
  if (type === "number") return Number(value);
  if (type === "integer") return Number.parseInt(value, 10);
  if (type === "boolean") return value === "true";
  if (type === "null") return null;
  if (type === "null") return null;
  return value;
}

function serializeSchema(schema) {
  const result = { type: schema.type };
  if (schema.type === "object") {
    result.properties = Object.fromEntries(schema.properties.filter(property => property.name.trim())
      .map(property => [property.name.trim(), serializeSchema(property.schema)]));
    if (schema.required.length) result.required = schema.required;
    result.additionalProperties = schema.additionalProperties;
  }
  if (schema.type === "array") result.items = serializeSchema(schema.items);
  return result;
}

export function toCondition(node) {
  if (node.kind === "group") return { [node.operator]: node.children.map(toCondition) };
  if (node.kind === "not") return { not: toCondition(node.child) };
  const path = node.path.trim();
  return {
    path: node.scope + (path.startsWith("[") ? "" : ".") + path,
    op: node.op,
    value: node.op === "jsonSchema" ? serializeSchema(node.schema) : valueOf(node),
    ...(node.ignoreCase ? { ignoreCase: true } : {})
  };
}

export function conditionSummary(node) {
  if (node.kind === "group") {
    const separator = node.operator === "all" ? " AND " : " OR ";
    return "(" + node.children.map(conditionSummary).join(separator) + ")";
  }
  if (node.kind === "not") return "NOT (" + conditionSummary(node.child) + ")";
  const label = OPERATORS.find(item => item[0] === node.op)?.[1] || node.op;
  const root = node.scope === "meta" ? "Ingestion metadata" : "Event data";
  const path = root + " · " + (node.path.trim() || "[field]");
  if (node.op === "exists") return path + (node.value === "false" ? " is missing" : " is present");
  if (node.op === "jsonSchema") return path + " matches the configured JSON structure";
  return path + " " + label.toLowerCase() + " “" + (node.value || "[value]") + "”";
}

export function toRulePayload(draft) {
  const teams = draft.teams.split(",").map(value => value.trim()).filter(Boolean);
  const users = draft.users.split(",").map(value => value.trim()).filter(Boolean);
  const payload = {
    name: draft.name.trim(),
    enabled: draft.enabled,
    priority: Number(draft.priority),
    route: { teams, users },
    v: 1,
    match: toCondition(draft.match),
    severity: draft.severity,
    title: draft.title.trim() || draft.name.trim(),
    body: draft.body,
    dedup: {
      keys: draft.dedupKeys.split(",").map(value => value.trim()).filter(Boolean),
      windowSec: Number(draft.windowSec)
    },
    stopProcessing: draft.stopProcessing,
    runbook: draft.runbook || "auto"
  };
  return payload;
}

export function validateDraft(draft) {
  if (!draft.name.trim()) return "Enter a rule name.";
  if (!draft.teams.trim() && !draft.users.trim()) return "Add at least one team or user recipient.";
  if (!Number.isInteger(Number(draft.priority))) return "Priority must be a whole number.";
  if (Number(draft.priority) < -2147483648 || Number(draft.priority) > 2147483647) return "Priority is outside the supported range.";
  if (draft.dedupKeys.split(",").filter(value => value.trim()).length > 10) return "Deduplication supports up to 10 fields.";
  if (!Number.isInteger(Number(draft.windowSec)) || Number(draft.windowSec) < 1 || Number(draft.windowSec) > 86400) {
    return "Deduplication window must be between 1 and 86,400 seconds.";
  }
  let count = 0;
  return validateNode(draft.match, 1, () => ++count > 100);
}

function validateNode(node, depth = 1, exceedsCount = () => false) {
  if (depth > 10) return "Rule conditions cannot exceed 10 nested levels.";
  if (exceedsCount()) return "A rule can contain up to 100 condition items.";
  if (node.kind === "group") {
    if (!node.children.length) return "Every condition group needs at least one condition.";
    if (node.children.length > 50) return "A condition group can contain up to 50 items.";
    for (const child of node.children) {
      const error = validateNode(child, depth + 1, exceedsCount);
      if (error) return error;
    }
    return null;
  }
  if (node.kind === "not") return validateNode(node.child, depth + 1, exceedsCount);
  if (!node.path.trim()) return "Enter a field name for each condition.";
  if (node.op === "regex" && !node.value.trim()) return "Enter a regular expression.";
  if (node.op === "regex" && node.value.length > 500) return "Regular expressions can contain up to 500 characters.";
  if (node.op === "in" || node.op === "nin") {
    const values = node.value.split(",").filter(value => value.trim());
    if (!values.length) return "Enter at least one list value.";
    if (values.length > 1000) return "Lists can contain up to 1,000 values.";
  } else if (node.op !== "exists" && node.op !== "jsonSchema" && !node.value.trim()) {
    return "Enter a comparison value for each condition.";
  }
  if (node.op === "jsonSchema") return validateSchema(node.schema, depth);
  if (["number", "integer"].includes(node.valueType) && node.op !== "exists") {
    const values = node.op === "in" || node.op === "nin" ? node.value.split(",") : [node.value];
    if (values.some(value => value.trim() === "" || !Number.isFinite(Number(value)))) return "Enter valid numeric values.";
  }
  return null;
}

function validateSchema(schema, depth) {
  if (depth > 10) return "JSON structure cannot exceed 10 nested levels.";
  if (schema.type === "object") {
    const names = schema.properties.map(property => property.name.trim());
    if (names.some(name => !name)) return "Give every JSON property a name.";
    if (new Set(names).size !== names.length) return "JSON property names must be unique.";
    for (const property of schema.properties) {
      const error = validateSchema(property.schema, depth + 1);
      if (error) return error;
    }
  }
  if (schema.type === "array") return validateSchema(schema.items, depth + 1);
  return null;
}
