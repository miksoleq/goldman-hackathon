import { chromium } from "playwright-core";
import { createInterface } from "node:readline/promises";

// The driver requests a snapshot; only the separate diagnostic command clicks Refresh.
const browsers = [];
const views = {};
try {
  for (const [name, url] of Object.entries({ panel: process.env.PANEL_URL, monitor: process.env.MONITOR_URL })) {
    const browser = await chromium.launch(process.env.CHROME_BIN
      ? { executablePath: process.env.CHROME_BIN } : { channel: "chrome" });
    browsers.push(browser);
    const page = await browser.newPage({ viewport: { width: 1440, height: 7000 } });
    const login = await page.request.post(url + "/api/auth/login", {
      data: { username: process.env.E2E_USERNAME, password: process.env.E2E_PASSWORD }
    });
    if (!login.ok()) throw Error(name + " login failed: " + login.status());
    const initial = page.waitForResponse(r => r.url().endsWith("/api/monitor/alerts") && r.status() === 200);
    await page.goto(url);
    await initial;
    const tab = name === "panel" ? page.getByRole("navigation").getByRole("button", { name: "Alerts" })
      : page.getByRole("tab", { name: "Alerts", exact: true });
    await tab.click();
    await page.getByRole("textbox", { name: "Search alerts" }).waitFor();
    views[name] = { page, titles: page.locator(name === "panel" ? ".activity-item strong" : ".list-item .item-title") };
  }
  console.log("ready");
  for await (const command of createInterface({ input: process.stdin })) {
    if (command === "refresh") {
      await Promise.all(Object.values(views).map(async ({ page }) => {
        await page.getByRole("button", { name: /^Refresh( data)?$/ }).click();
        // After both profiles, the diagnostic must fit both batches of rows.
        await page.setViewportSize({ width: 1440, height: 16000 });
      }));
    }
    const snapshot = {};
    for (const [name, { titles }] of Object.entries(views)) {
      snapshot[name] = await titles.evaluateAll(nodes => nodes.filter(node => {
        const rect = node.getBoundingClientRect();
        return !document.hidden && node.checkVisibility() && rect.top >= 0 && rect.bottom <= innerHeight &&
          rect.width > 0 && rect.height > 0;
      }).map(node => node.textContent.trim()));
    }
    console.log(JSON.stringify(snapshot));
  }
} finally {
  await Promise.all(browsers.map(browser => browser.close()));
}
