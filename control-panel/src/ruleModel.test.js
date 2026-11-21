import assert from "node:assert/strict";
import test from "node:test";
import { conditionSummary, createGroup, createLeaf, createSchema, toCondition, toRulePayload, validateDraft } from "./ruleModel.js";

function draft() {
  return {
    name: "Risk rule", enabled: true, priority: 100, match: createGroup(), teams: "Market Risk", users: "",
    severity: "high", title: "", body: "", runbook: "auto", dedupKeys: "", windowSec: 60, stopProcessing: false
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
  const schema = createSchema("object");
  schema.properties = [
    { id: "1", name: "amount", schema: createSchema("number") },
    { id: "2", name: "items", schema: { ...createSchema("array"), items: createSchema("integer") } }
  ];
  schema.required = ["amount"];
  schema.additionalProperties = false;
  const leaf = { ...createLeaf(), path: "transaction", op: "jsonSchema", schema };

  assert.deepEqual(toCondition(leaf), {
    path: "payload.transaction",
    op: "jsonSchema",
    value: {
      type: "object",
      properties: { amount: { type: "number" }, items: { type: "array", items: { type: "integer" } } },
      required: ["amount"],
      additionalProperties: false
    }
  });
});

test("builds creation payload with teams, users, runbook, and alert options", () => {
  const rule = draft();
  rule.teams = " Market Risk, Operations ";
  rule.users = " analyst ";
  rule.runbook = "Settlement guide";
  rule.body = "Review transaction";
  rule.dedupKeys = "payload.tradeId, meta.source";
  rule.match.children[0].path = "tradeId";
  const payload = toRulePayload(rule);

  assert.deepEqual(payload.route, { teams: ["Market Risk", "Operations"], users: ["analyst"] });
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
  rule.windowSec = 86401;
  assert.match(validateDraft(rule), /Deduplication window/);
  rule.windowSec = 60;
  rule.teams = "";
  assert.match(validateDraft(rule), /recipient/);
});
