import assert from "node:assert/strict";
import test from "node:test";
import { createRule, fetchFeed } from "./api.js";

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

test("session requests send the same-origin cookie and signal expired sessions", async () => {
  const { sessionRequest } = await import("../../web-shared/session.js");
  const events = new EventTarget();
  let expired = false;
  events.addEventListener("session-expired", () => { expired = true; });
  const previous = globalThis.window;
  globalThis.window = events;
  try {
    await sessionRequest("/api/rules", { method: "POST" }, async (url, options) => {
      assert.equal(options.credentials, "same-origin");
      return { status: 401 };
    });
    assert.equal(expired, true);
  } finally {
    if (previous === undefined) delete globalThis.window;
    else globalThis.window = previous;
  }
});
