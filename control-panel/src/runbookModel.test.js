import test from "node:test";
import assert from "node:assert/strict";
import { bookResource, draftFromBook, executionMode, formats, instructionError, moveAction } from "./runbookModel.js";
import { loadRunbookEditor, saveRunbook } from "./api.js";

test("preserves multiline instructions in all three authoring formats", () => {
  assert.deepEqual(formats, ["text", "markdown", "yaml"]);
  for (const format of formats) {
    const draft = draftFromBook({ name: "Guide", format, content: "steps:\n  - Review\n" });
    assert.equal(instructionError(draft), "");
    assert.equal(bookResource(draft).spec.content, draft.content);
  }
  const legacy = draftFromBook({ name: "Guide", format: "url", content: "https://example.test" });
  assert.equal(legacy.format, "text");
  assert.equal(bookResource(legacy).spec.content, "https://example.test");
  assert.match(instructionError({ ...legacy, name: "none" }), /reserved/);
  assert.match(instructionError({ ...legacy, format: "yaml", content: "list:\n  item: [1\n" }), /line \d/);
});

test("reorders steps without losing script settings in a steps-only runbook", () => {
  const script = { type: "python", code: "def run(ctx): return 1", approval: "none", timeoutMs: 2000, params: {} };
  const next = { type: "escalate", params: { team: "ops" } };
  const draft = draftFromBook({ name: "Guide", format: "text", content: "", actions: [next, script] });
  draft.actions = moveAction(draft.actions, 1, 0);
  assert.equal(instructionError(draft), "");
  assert.deepEqual(bookResource(draft).spec, { steps: [script, next] });
  assert.equal(executionMode(script, { autoExecute: true, approvalRequired: true }), "Automatic");
  assert.equal(executionMode(next, { autoExecute: true, approvalRequired: true }), "Approval required");
});

test("editor uses the fetched ETag, surfaces conflicts and creates with apply", async () => {
  const book = { id: "id1", actions: [] };
  const data = await loadRunbookEditor(book.id, undefined, async url =>
    url === "/api/action-types" ? Response.json([]) : Response.json(book, { headers: { ETag: '"v1"' } }));
  await assert.rejects(saveRunbook(book.id, data.etag, {}, async (url, options) => {
    assert.equal(url, "/api/runbooks/id1");
    assert.equal(options.method, "PUT");
    assert.equal(options.headers["If-Match"], '"v1"');
    return new Response(null, { status: 412 });
  }), /Someone updated/);
  const resource = bookResource(draftFromBook({ name: "Book", content: "Read" }));
  assert.deepEqual(await saveRunbook(null, null, resource, async (url, options) => {
    assert.equal(url, "/api/apply");
    assert.equal(options.method, "POST");
    assert.deepEqual(JSON.parse(options.body), resource);
    return Response.json(book, { status: 201 });
  }), book);
});
