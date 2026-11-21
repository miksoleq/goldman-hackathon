async (page, engineUrl = "http://localhost:8081") => {
  const name = "Duże nieudane płatności " + crypto.randomUUID();
  const source = `# Preserve this operator comment on reopen.
name: ${name}
enabled: true
priority: 10
match:
  all:
    - { path: payload.type, op: eq, value: payment_failed }
    - any:
        - { path: payload.amount, op: gt, value: 1000 }
        - { path: payload.customer.tier, op: eq, value: vip }
    - not: { path: payload.env, op: eq, value: test }
severity: "{{payload.level | default: high}}"
title: "Płatność {{payload.amount}} {{payload.currency}} odrzucona"
body: "Klient {{payload.customer.id}}, kod {{payload.error.code | default: brak}}"
dedup:
  keys: [payload.customer.id, payload.error.code]
  windowSec: 60
route:
  teams: [payments, finance-ops]
runbook: auto
stopProcessing: false
`;
  await page.getByRole("button", { name: "Create rule", exact: true }).first().click();
  await page.getByRole("button", { name: "Write YAML" }).click();
  const editor = page.getByLabel("Rule YAML");
  const invalid = source.replace("default: high", "unknownFilter");
  await editor.fill(invalid);
  await page.getByRole("button", { name: "Create rule", exact: true }).click();
  await page.getByRole("alert").filter({ hasText: "unknownFilter" }).waitFor();
  if (await editor.inputValue() !== invalid) throw new Error("Validation discarded the submitted YAML");

  await editor.fill(source);
  const [creation] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith("/api/rules") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Create rule", exact: true }).click()
  ]);
  if (creation.status() !== 201) throw new Error(await creation.text());
  const { id } = await creation.json();
  await page.getByText("Engine · v1 active", { exact: true }).waitFor();
  const edit = page.getByRole("button", { name: "Edit YAML" });
  await edit.click();
  if (await editor.inputValue() !== source) throw new Error("Stored source changed on reopen");

  const updated = source.replace("Płatność {{", "Odmowa {{");
  await editor.fill(updated);
  await page.getByRole("button", { name: "Save changes" }).click();
  await page.getByText("Engine · v2 active", { exact: true }).waitFor();
  await edit.click();
  if (await editor.inputValue() !== updated) throw new Error("Edited source did not persist");

  const concurrent = updated.replace("priority: 10", "priority: 11");
  const competingSave = await page.request.put(new URL("/api/rules/" + id, page.url()).href, {
    headers: { "If-Match": '"2"', "Content-Type": "application/yaml" }, data: concurrent
  });
  if (!competingSave.ok()) throw new Error(await competingSave.text());
  await page.getByRole("button", { name: "Save changes" }).click();
  await page.getByRole("alert").filter({ hasText: "Another edit was saved" }).waitFor();
  if (await editor.inputValue() !== updated) throw new Error("Version conflict discarded local edits");
  await page.getByRole("button", { name: "Cancel" }).click();
  await page.getByText("RULE · VERSION 3", { exact: true }).waitFor();
  await page.getByText("Engine · v3 active", { exact: true }).waitFor();
  await edit.click();
  if (await editor.inputValue() !== concurrent) throw new Error("Reopen did not load the latest source");

  const ingested = await page.request.post(engineUrl + "/ingest", {
    data: { type: "payment_failed", amount: 1500, currency: "PLN", customer: { id: name } }
  });
  if (ingested.status() !== 202) throw new Error(await ingested.text());
  const { eventId } = await ingested.json();
  await page.waitForFunction(async ({ eventId, id, name }) => {
    const alerts = await (await fetch("/api/monitor/alerts")).json();
    return alerts.some(alert => alert.eventId === eventId && alert.ruleId === id &&
      alert.title === "Odmowa 1500 PLN odrzucona" && alert.severity.toLowerCase() === "high" &&
      alert.body === "Klient " + name + ", kod brak");
  }, { eventId, id, name }, { timeout: 15000, polling: 250 });
  return { id, version: 3, eventId };
}
