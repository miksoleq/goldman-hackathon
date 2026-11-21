import test from "node:test";
import assert from "node:assert/strict";
import { newAlerts } from "../../web-shared/liveAlerts.js";

test("only newly received alerts trigger notifications", () => {
  const old = { alertId: "old", count: 1 };
  const fresh = { alertId: "new", count: 1 };
  assert.deepEqual(newAlerts(null, [old]), []);
  assert.deepEqual(newAlerts([old], [{ ...old, count: 2 }]), []);
  assert.deepEqual(newAlerts([old], [fresh, old]), [fresh]);
  assert.deepEqual(newAlerts([fresh, old], [fresh, old]), []);
});
