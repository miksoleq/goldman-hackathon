import assert from "node:assert/strict";
import { AlertsClient } from "../clients/alerts.mjs";
import { Monitor } from "../pages/monitor.mjs";

export async function pythonApproval(page, settings) {
  const login = await page.request.post(settings.control + "/api/auth/login", {
    data: { username: settings.username, password: settings.password }
  });
  assert.equal(login.status(), 200, "Demo user can sign in");
  const alerts = new AlertsClient(page.request, settings.control, settings.engine);
  const rule = await alerts.createRule();

  await page.goto(settings.panel);
  await page.getByRole("navigation", { name: "Main navigation" }).getByRole("button", { name: /^Rules/ }).click();
  await page.getByRole("button", { name: new RegExp(rule.tag) }).click();
  await page.getByRole("heading", { name: rule.tag, exact: true }).waitFor();

  await page.getByText("Engine · v1 active", { exact: true }).waitFor();

  const monitorPage = await page.context().newPage();
  const monitor = new Monitor(monitorPage, settings.monitor);
  const baseline = monitorPage.waitForResponse(response =>
    response.url().endsWith("/api/me/notifications") && response.ok());
  await monitor.open();
  await baseline;
  let alert;
  await waitFor(async () => {
    await alerts.ingest(rule.tag);
    const feed = await (await page.request.get(settings.control + "/api/monitor/alerts")).json();
    alert = feed.find(item => item.ruleId === rule.id);
    return !!alert;
  });
  for (const view of [page, monitorPage]) {
    await view.getByRole("status", { name: "New alert" }).getByText(rule.tag, { exact: true }).waitFor();
    await view.getByRole("button", { name: "Dismiss alert notification" }).click();
  }
  await monitor.openAlert(rule.tag);
  let detail;
  await waitFor(async () => (await alerts.detail(alert.alertId)).actions?.[1]?.available === true);
  detail = await alerts.detail(alert.alertId);
  assert.equal(detail.actions[0].result.response.status, "UP");
  assert.equal(detail.actions[1].status, "pending_approval");
  assert.equal(detail.actions[1].approvalRequired, true);

  const unconfirmed = await page.request.post(settings.control + "/api/alerts/" + alert.alertId + "/actions/2/execute", {
    data: { confirmed: false }
  });
  assert.equal(unconfirmed.status(), 409, "Confirmation is required");

  await monitor.approvePython();
  await monitor.waitForResult();
  detail = await alerts.detail(alert.alertId);
  assert.deepEqual(detail.actions[1].result.result, { service: "script-runner", healthy: true });
  assert.equal(detail.actions[1].approvedBy, settings.username);
  await monitorPage.reload();
  await monitor.open();
  await monitor.openAlert(rule.tag);
  await monitor.waitForResult();

  const initialCount = detail.count;
  await alerts.ingest(rule.tag);
  await waitFor(async () => (await alerts.detail(alert.alertId)).count > initialCount);
  detail = await alerts.detail(alert.alertId);
  assert.equal(detail.actions.length, 2, "Deduplication preserves the original steps");
  assert.equal(detail.actions[1].status, "succeeded");
}

async function waitFor(check) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw Error("Timed out waiting for alert state");
}
