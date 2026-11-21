import assert from "node:assert/strict";
import test from "node:test";
import { FEED_NAMES, fetchMonitorFeed } from "./api.js";

test("loads each monitor feed from its matching API route", async () => {
  const requestedUrls = [];
  const request = async (url, options) => {
    requestedUrls.push(url);
    assert.equal(options.headers.Accept, "application/json");
    return { ok: true, json: async () => [] };
  };

  for (const name of FEED_NAMES) {
    assert.deepEqual(await fetchMonitorFeed(name, request), []);
  }

  assert.deepEqual(requestedUrls, [
    "/api/monitor/rules",
    "/api/monitor/runbooks",
    "/api/monitor/alerts"
  ]);
});

test("rejects failed and malformed monitor responses", async () => {
  await assert.rejects(
    fetchMonitorFeed("alerts", async () => ({ ok: false })),
    /request failed/
  );
  await assert.rejects(
    fetchMonitorFeed("alerts", async () => ({ ok: true, json: async () => ({}) })),
    /invalid response/
  );
  await assert.rejects(fetchMonitorFeed("unknown", async () => ({})), TypeError);
});
