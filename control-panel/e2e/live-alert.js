async (page, engineUrl, monitorUrl) => {
  const context = page.context();
  const monitor = await context.newPage();
  try {
    // Wait for both initial snapshots so historical rows cannot trigger this notification.
    async function open(tab, url) {
      const baseline = tab.waitForResponse(res => res.url().endsWith("/api/monitor/alerts/live") && res.status() === 200);
      await tab.goto(url);
      await baseline;
    }
    await open(page, page.url());
    await page.getByRole("button", { name: "Log in", exact: true }).click();
    await page.getByRole("button", { name: "Demo Member" }).click();
    await page.getByText("demo.member", { exact: true }).waitFor();
    await open(monitor, monitorUrl);
    const tag = "Live browser " + crypto.randomUUID();
    const rule = await page.request.post(new URL("/api/rules", page.url()).href, { data: {
      name: tag, title: tag, route: { teams: ["demo-operations"] },
      match: { path: "payload.probe", op: "eq", value: tag },
      dedup: { keys: ["payload.probe"], windowSec: 60 }
    } });
    if (rule.status() !== 201) throw new Error(await rule.text());
    await page.getByRole("button", { name: "Refresh data" }).click();
    await page.getByRole("navigation").getByRole("button", { name: /^Rules/ }).click();
    await page.getByRole("button", { name: new RegExp(tag) }).click();
    await page.getByText("Engine · v1 active", { exact: true }).waitFor();
    let navigated = false;
    page.on("framenavigated", () => { navigated = true; });
    await page.getByRole("button", { name: "Alerts", exact: true }).click();
    const start = Date.now();
    async function ingest() {
      const response = await page.request.post(engineUrl + "/ingest", { data: { probe: tag } });
      if (response.status() !== 202) throw new Error(await response.text());
    }
    await ingest();
    for (const tab of [page, monitor]) {
      await tab.getByRole("status", { name: "New alert" }).getByText(tag).waitFor({ timeout: 5000 });
    }
    const elapsedMs = Date.now() - start;
    await page.getByRole("button", { name: "Dismiss alert notification" }).click();
    await page.getByRole("button", { name: new RegExp(tag) }).click();
    await ingest();
    await page.locator(".activity-detail pre").filter({ hasText: '"count": 2' }).waitFor({ timeout: 5000 });
    if (await page.getByRole("status", { name: "New alert" }).count() || navigated) {
      throw new Error("A duplicate notification or navigation occurred");
    }
    await page.getByRole("button", { name: "Log out" }).click();
    await page.getByRole("button", { name: "Log in", exact: true }).waitFor();
    return { elapsedMs, count: 2 };
  } finally {
    await monitor.close();
  }
}
