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

export function operatorsForType(type) {
  const equality = ["exists","eq","ne","in","nin","jsonSchema"];
  const numeric = ["gt","gte","lt","lte"];
  const text = ["contains","startsWith","endsWith","regex"];
  const allowed = [...equality];
  if (["number","integer","string"].includes(type)) allowed.push(...numeric);
  if (["number","integer","string","boolean"].includes(type)) allowed.push(...text);
  if (type === "array") allowed.push("contains");
  return OPERATORS.filter(([op]) => allowed.includes(op));
}

export function schemaFromExample(value) {
  if (value === null) return { type: "null" };
  if (Array.isArray(value)) {
    if (value.length === 0) return { type: "array" };
    const schemas = value.map(schemaFromExample);
    if (schemas.some(schema => JSON.stringify(schema) !== JSON.stringify(schemas[0]))) throw new Error("Array items must have the same structure.");
    return { type: "array", items: schemas[0] };
  }
  if (typeof value === "object") return {
    type: "object",
    properties: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, schemaFromExample(item)])),
    required: Object.keys(value),
    additionalProperties: false
  };
  return { type: typeof value };
}

function valueOf(condition) {
  if (condition.op === "exists") return condition.value !== "false";
  if (condition.op === "jsonSchema") return condition.schema;
  if (["gt","gte","lt","lte"].includes(condition.op)) return Number(condition.value);
  if ((condition.op === "contains" && condition.valueType !== "array") || ["startsWith","endsWith","regex"].includes(condition.op)) return condition.value;
  if (["object","array"].includes(condition.valueType)) return JSON.parse(condition.value);
  if (condition.op === "in" || condition.op === "nin") {
    return condition.value.split(",").map(value => value.trim()).filter(Boolean)
      .map(value => castValue(value, condition.valueType));
  }
  return castValue(condition.value, condition.valueType);
}

function castValue(value, type) {
  if (type === "number") return Number(value);
  if (type === "integer") return Number(value);
  if (type === "boolean") return value !== "false";
  if (type === "null") return null;
  return value;
}

export function toCondition(node) {
  if (node.kind === "group") return { [node.operator]: node.children.map(toCondition) };
  if (node.kind === "not") return { not: toCondition(node.child) };
  const path = node.path.trim();
  return {
    path: node.op === "jsonSchema" ? "payload" : node.scope + (path ? (path.startsWith("[") ? "" : ".") + path : ""),
    op: node.op,
    value: node.op === "jsonSchema" ? schemaFromExample(JSON.parse(node.example)) : valueOf(node),
    ...(node.ignoreCase && node.op !== "jsonSchema" ? { ignoreCase: true } : {})
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
  if (node.op === "jsonSchema") return "Event data matches the configured JSON structure";
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

export function validateDraft(draft, step = "all") {
  if (!draft.name.trim()) return "Enter a rule name.";
  if (step !== "trigger" && !draft.teams.trim() && !draft.users.trim()) return "Add at least one team or user recipient.";
  if (String(draft.priority).trim() === "" || !Number.isInteger(Number(draft.priority))) return "Priority must be a whole number.";
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
  if (!node.path.trim() && node.op !== "jsonSchema") return "Enter a field name for each condition.";
  const path = node.scope + (node.path.trim() ? (node.path.trim().startsWith("[") ? "" : ".") + node.path.trim() : "");
  if (!/^(payload|meta)(\.[^.\[\]'\s]+|\[([0-9]+|\*)\]|\['[^']+'\])*$/.test(path) || path.length > 300) return "Enter a valid field name.";
  if (node.op === "regex" && !node.value.trim()) return "Enter a regular expression.";
  if (node.op === "regex" && node.value.length > 500) return "Regular expressions can contain up to 500 characters.";
  if (["object", "array"].includes(node.valueType) && !["exists", "jsonSchema"].includes(node.op)) {
    let value;
    try { value = JSON.parse(node.value); } catch { return "Enter the comparison value as valid JSON."; }
    if (["in","nin"].includes(node.op) && (!Array.isArray(value) || !value.length || value.length > 1000)) {
      return "Enter a JSON array of 1 to 1,000 comparison values.";
    }
    return null;
  }
  if (node.op === "in" || node.op === "nin") {
    const values = node.value.split(",").map(value => value.trim()).filter(Boolean);
    if (!values.length) return "Enter at least one list value.";
    if (node.valueType === "boolean" && values.some(value => value !== "true" && value !== "false")) return "Enter true or false for each list value.";
    if (node.valueType === "null" && values.some(value => value !== "null")) return "Enter null as the list value.";
    if (values.length > 1000) return "Lists can contain up to 1,000 values.";
  } else if (node.op !== "exists" && node.op !== "jsonSchema" && node.valueType !== "null" && node.valueType !== "boolean" && !node.value.trim()) {
    return "Enter a comparison value for each condition.";
  }
  if (node.op === "jsonSchema") {
    try { schemaFromExample(JSON.parse(node.example)); } catch (error) { return "Enter valid example JSON. " + (error instanceof SyntaxError ? "" : error.message); }
    return null;
  }
  if ((["number", "integer"].includes(node.valueType) || ["gt","gte","lt","lte"].includes(node.op)) && !["exists","regex","startsWith","endsWith","contains"].includes(node.op)) {
    const values = node.op === "in" || node.op === "nin" ? node.value.split(",") : [node.value];
    if (values.some(value => value.trim() === "" || !Number.isFinite(Number(value)) || (node.valueType === "integer" && !Number.isInteger(Number(value))))) return "Enter valid numeric values.";
  }
  return null;
}
