import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

export async function inlinePython(page, settings) {
  const tag = "Inline " + randomUUID();
  await page.goto(settings.panel + "/#/rules/new");
  await page.getByLabel("Rule name").fill(tag);
  await page.getByLabel("Field name").fill("probe");
  await page.getByLabel("Value", { exact: true }).fill(tag);
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel("Users", { exact: true }).fill(settings.username);
  await page.getByRole("option").getByRole("button", { name: settings.username }).click();
  await page.getByRole("button", { name: "Create a new runbook" }).click();
  await page.getByLabel("Runbook name").fill(tag + " book");
  for (let i = 0; i < 2; i++) {
    await page.getByLabel("Action type").selectOption("python");
    await page.getByRole("button", { name: "Add action" }).click();
  }
  await page.getByLabel("Python code").nth(0).fill('def run(ctx):\n    return {"probe": ctx["event"]["payload"]["probe"]}');
  await page.getByRole("combobox", { name: /^Execution/ }).nth(0).selectOption("none");
  assert.equal(await page.getByRole("combobox", { name: /^Execution/ }).nth(1).inputValue(), "required");
  await page.getByLabel("Python code").nth(1).fill('def run(ctx):\n    return {"reviewed": ctx["previousStepResult"]["probe"]}');
  await page.getByRole("button", { name: "Continue" }).click();
  const saved = page.waitForResponse(r => r.url().endsWith("/api/rules") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Create rule", exact: true }).click();
  assert.equal((await saved).status(), 201);
  await page.getByRole("button", { name: new RegExp(tag) }).click();
  await page.getByText("Engine · v1 active", { exact: true }).waitFor();
  const event = await page.request.post(settings.engine + "/ingest", { data: { probe: tag } });
  assert.equal(event.status(), 202);
  const deadline = Date.now() + 15000;
  let alert;
  while (Date.now() < deadline) {
    const feed = await (await page.request.get(settings.control + "/api/monitor/alerts")).json();
    alert = feed.find(row => row.title === tag);
    if (alert) {
      const detail = await (await page.request.get(settings.control + "/api/alerts/" + alert.alertId)).json();
      if (detail.actions[1]?.available) break;
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  assert.ok(alert);
  await page.getByRole("button", { name: "Refresh data" }).click();
  await page.getByRole("navigation").getByRole("button", { name: "Alerts" }).click();
  await page.getByRole("button", { name: new RegExp(tag) }).click();
  await page.getByRole("button", { name: "Python Script", exact: true }).click();
  await page.getByRole("button", { name: "Confirm", exact: true }).click();
  await page.locator("pre").filter({ hasText: '"reviewed": "' + tag + '"' }).waitFor();
  const detail = await (await page.request.get(settings.control + "/api/alerts/" + alert.alertId)).json();
  assert.deepEqual(detail.actions.map(step => step.status), ["succeeded", "succeeded"]);
  assert.equal(detail.actions[1].approvedBy, settings.username);
}
