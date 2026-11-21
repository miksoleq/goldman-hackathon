import assert from "node:assert/strict";
import test from "node:test";
import { createRule, fetchFeed, saveRuleYaml } from "./api.js";

test("loads monitor feeds and validates their shape", async () => {
  let requested;
  const items = await fetchFeed("rules", async (url, options) => {
    requested = [url, options];
    return { ok: true, json: async () => [{ id: "rule-1" }] };
  });
  assert.equal(requested[0], "/api/monitor/rules");
  assert.equal(requested[1].cache, "no-store");
  assert.deepEqual(items, [{ id: "rule-1" }]);
  await assert.rejects(fetchFeed("rules", async () => ({ ok: true, json: async () => ({}) })), /invalid rules response/);
});

test("posts a rule as JSON and surfaces duplicate and validation errors", async () => {
  let requestBody;
  const result = await createRule({ name: "risk" }, async (url, options) => {
    assert.equal(url, "/api/rules");
    assert.equal(options.method, "POST");
    assert.equal(options.headers["Content-Type"], "application/json");
    requestBody = JSON.parse(options.body);
    return { ok: true, json: async () => ({ id: "id-1" }) };
  });
  assert.deepEqual(requestBody, { name: "risk" });
  assert.deepEqual(result, { id: "id-1" });
  await assert.rejects(createRule({}, async () => ({ ok: false, status: 409, json: async () => ({}) })), /already exists/);
  await assert.rejects(createRule({}, async () => ({ ok: false, status: 400, json: async () => ({ message: "Bad field" }) })), /Bad field/);
});

test("sends the original YAML and protects edits with the loaded version", async () => {
  const source = "# original formatting\nname: Trade\n";
  await saveRuleYaml(source, { id: "rule", version: 3 }, async (url, options) => {
    assert.equal(url, "/api/rules/rule");
    assert.equal(options.method, "PUT");
    assert.equal(options.headers["If-Match"], '"3"');
    assert.equal(options.headers["Content-Type"], "application/yaml");
    assert.equal(options.body, source);
    return { ok: true, json: async () => ({ version: 4 }) };
  });
});
