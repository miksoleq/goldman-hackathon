import { chromium } from "playwright-core";
import { mkdir } from "node:fs/promises";
import { pythonApproval } from "../scenarios/python-approval.mjs";

const settings = {
  panel: process.env.PANEL_URL,
  monitor: process.env.MONITOR_URL,
  control: process.env.CONTROL_URL,
  engine: process.env.ENGINE_URL,
  username: process.env.E2E_USERNAME,
  password: process.env.E2E_PASSWORD
};
for (const [key, value] of Object.entries(settings)) {
  if (!value) throw Error("Missing E2E setting: " + key);
}
const browser = await chromium.launch(process.env.CHROME_BIN
  ? { executablePath: process.env.CHROME_BIN } : { channel: "chrome" });
const context = await browser.newContext();
await context.tracing.start({ screenshots: true, snapshots: true });
const page = await context.newPage();
page.setDefaultTimeout(15000);
try {
  await pythonApproval(page, settings);
  console.log("PASS: rule → event → alert → approved Python → persisted history and deduplication");
} catch (error) {
  await mkdir("e2e-results", { recursive: true });
  await page.screenshot({ path: "e2e-results/failure.png", fullPage: true });
  await context.tracing.stop({ path: "e2e-results/trace.zip" });
  console.error(error);
  process.exitCode = 1;
} finally {
  await context.close();
  await browser.close();
}
