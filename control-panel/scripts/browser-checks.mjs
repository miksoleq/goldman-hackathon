import { chromium } from "playwright-core";
import { readFile, readdir } from "node:fs/promises";
const base = process.env.PANEL_URL || "http://localhost:8083";
const directory = new URL("../e2e/", import.meta.url);
const browser = await chromium.launch(process.env.CHROME_BIN ? { executablePath: process.env.CHROME_BIN } : { channel: "chrome" });

try {
  for (const file of (await readdir(directory)).filter(name => name.endsWith(".js")).sort()) {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.setDefaultTimeout(8000);
    try {
      // Each scenario starts with an isolated browser session and the same live demo backend.
      if (file !== "accounts.js") {
        const login = await page.request.post(base + "/api/auth/login", {
          data: { username: "demo.member", password: "Runbook123!" }
        });
        if (!login.ok()) throw new Error("Demo login failed");
      }
      await page.goto(base);
      if (file === "docker-alert-check.js") await page.getByRole("button", { name: "Create rule", exact: true }).first().click();
      const source = await readFile(new URL(file, directory), "utf8");
      const { default: scenario } = await import("data:text/javascript;base64," + Buffer.from("export default " + source).toString("base64"));
      await scenario(page);
      console.log("PASS", file);
    } catch (e) {
      console.error("FAIL", file, e.message); process.exitCode = 1;
    } finally { await context.close(); }
  }
} finally { await browser.close(); }
