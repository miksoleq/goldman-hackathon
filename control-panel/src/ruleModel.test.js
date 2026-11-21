import { newAction, draftFromBook } from "./runbookModel.js";
import assert from "node:assert/strict";
import test from "node:test";
import { conditionSummary, createGroup, createLeaf, operatorsForType, schemaFromExample, toCondition, toRulePayload, validateDraft } from "./ruleModel.js";

function draft() {
  return {
    name: "Risk rule", enabled: true, priority: 100, match: createGroup(), teams: "Market Risk", users: "",
    channels: ["ui"], severity: "high", title: "", body: "", runbook: "auto", dedupKeys: "", windowSec: 60, stopProcessing: false
  };
}

test("serializes nested all, any, and not conditions with backend field paths", () => {
  const root = createGroup();
  const amount = createLeaf();
  Object.assign(amount, { path: "amount", op: "gte", value: "100000", valueType: "number", scope: "payload" });
  const nested = { ...createGroup(), operator: "any", children: [] };
  const status = createLeaf();
  Object.assign(status, { scope: "meta", path: "source", op: "eq", value: "partner-feed" });
  nested.children.push(status, { kind: "not", child: { ...createLeaf(), path: "blocked", op: "exists", value: "true" } });
  root.children = [amount, nested];

  assert.deepEqual(toCondition(root), {
    all: [
      { path: "payload.amount", op: "gte", value: 100000 },
      { any: [
        { path: "meta.source", op: "eq", value: "partner-feed" },
        { not: { path: "payload.blocked", op: "exists", value: true } }
      ] }
    ]
  });
  assert.match(conditionSummary(root), /AND/);
  assert.match(conditionSummary(root), /OR/);
  assert.match(conditionSummary(root), /NOT/);
});

test("serializes recursive JSON object and array structures", () => {
  const leaf = { ...createLeaf(), path: "transaction", op: "jsonSchema", example: '{"amount":42,"items":[1]}' };

  assert.deepEqual(toCondition(leaf), {
    path: "payload",
    op: "jsonSchema",
    value: {
      type: "object",
      properties: { amount: { type: "number" }, items: { type: "array", items: { type: "number" } } },
      required: ["amount", "items"],
      additionalProperties: false
    }
  });
});

test("filters operators by field type and rejects mixed example arrays", () => {
  assert.equal(operatorsForType("string").some(([op]) => op === "gt"), true);
  assert.equal(operatorsForType("number").some(([op]) => op === "gt"), true);
  assert.throws(() => schemaFromExample([1, "text"]), /same structure/);
});

test("builds creation payload with teams, users, runbook, and alert options", () => {
  const rule = draft();
  rule.teams = " Market Risk, Operations ";
  rule.users = " analyst ";
  rule.runbook = "Settlement guide";
  rule.channels = ["email", "sms"];
  rule.body = "Review transaction";
  rule.dedupKeys = "payload.tradeId, meta.source";
  rule.match.children[0].path = "tradeId";
  const payload = toRulePayload(rule);

  assert.deepEqual(payload.route, { teams: ["Market Risk", "Operations"], users: ["analyst"] });
  assert.deepEqual(payload.channels, ["email", "sms"]);
  assert.equal(payload.runbook, "Settlement guide");
  assert.deepEqual(payload.dedup.keys, ["payload.tradeId", "meta.source"]);
  assert.equal(payload.title, "Risk rule");
});

test("validates required recipients, condition values, and deduplication bounds", () => {
  const rule = draft();
  assert.equal(validateDraft(rule), "Enter a field name for each condition.");
  rule.match.children[0].path = "amount";
  rule.match.children[0].value = "100";
  assert.equal(validateDraft(rule), null);
  rule.channels = [];
  assert.match(validateDraft(rule), /notification channel/);
  assert.equal(validateDraft(rule, "trigger"), null);
  rule.channels = ["sms"];
  rule.windowSec = 86401;
  assert.match(validateDraft(rule), /Deduplication window/);
  rule.windowSec = 60;
  rule.teams = "";
  assert.match(validateDraft(rule), /recipient/);
});

test("reports empty required inputs in English without browser validation", () => {
  const rule = draft();
  rule.name = " ";
  assert.equal(validateDraft(rule, "trigger"), "Enter a rule name.");
  rule.name = "Rule";
  assert.equal(validateDraft(rule, "trigger"), "Enter a field name for each condition.");
  rule.match.children[0].path = "amount";
  rule.match.children[0].value = "10";
  rule.priority = "";
  assert.equal(validateDraft(rule), "Priority must be a whole number.");
});

test("saves typed JSON comparisons and a severity template", () => {
  const rule = draft();
  const leaf = { ...createLeaf(), path: "items", op: "contains", valueType: "array", value: '{"sku":"A"}' };
  rule.match = leaf;
  rule.severity = "{{payload.level}}";
  assert.equal(validateDraft(rule), null);
  assert.deepEqual(toRulePayload(rule).match.value, { sku: "A" });
  assert.equal(toRulePayload(rule).severity, "{{payload.level}}");
  leaf.value = "bad JSON";
  assert.match(validateDraft(rule), /valid JSON/);
  Object.assign(leaf, { valueType: "object", op: "eq", value: '{"code":404}' });
  assert.deepEqual(toCondition(leaf).value, { code: 404 });
});

test("keeps text fragments for scalar contains and validates typed lists", () => {
  const rule = draft();
  rule.match = { ...createLeaf(), path: "status", valueType: "boolean", op: "contains", value: "ru" };
  assert.equal(toCondition(rule.match).value, "ru");
  Object.assign(rule.match, { op: "in", value: "false, true" });
  assert.equal(validateDraft(rule), null);
  assert.deepEqual(toCondition(rule.match).value, [false, true]);
  rule.match.value = "true, maybe";
  assert.match(validateDraft(rule), /true or false/);
  Object.assign(rule.match, { valueType: "integer", value: "1e2" });
  assert.deepEqual(toCondition(rule.match).value, [100]);
});

test("creates an inline actions-only runbook and keeps named references on switching back", () => {
  const rule = draft();
  Object.assign(rule.match.children[0], { path: "service", value: "payments" });
  rule.runbook = "Existing guide";
  rule.createRunbook = true;
  rule.inlineBook = draftFromBook({ name: "Investigate" });
  const step = newAction({ name: "python", params: [] });
  assert.equal(step.approval, "required");
  assert.match(step.code, /def run\(ctx\)/);
  rule.inlineBook.actions.push(step);
  assert.equal(validateDraft(rule), null);
  assert.deepEqual(toRulePayload(rule).runbook, { name: "Investigate", steps: [step] });
  step.code = "  ";
  assert.match(validateDraft(rule), /Python script/);
  rule.createRunbook = false;
  assert.equal(validateDraft(rule), null);
  assert.equal(toRulePayload(rule).runbook, "Existing guide");
});
