async page => {
  const panel = new URL(page.url()).origin;
  const name = "Editor " + Date.now();
  const button = label => page.getByRole("button", { name: label, exact: true });
  const instructions = page.getByLabel("Instructions", { exact: true });
  const first = "steps:\n  - Check queue\n";
  const second = "steps:\n  - Check settlement\n";
  const addEscalation = async team => {
    await page.getByLabel("Action type").selectOption("escalate");
    await button("Add action").click();
    await page.getByLabel("team", { exact: true }).last().fill(team);
  };

  await button("Runbooks").click();
  await button("Create runbook").click();
  await page.getByLabel("Runbook name").fill(name);
  await page.getByLabel("Format").selectOption("yaml");
  await instructions.fill("steps:\n  invalid: [\n");
  await button("Continue").focus();
  await page.keyboard.press("Enter");
  await page.getByRole("alert").filter({ hasText: /YAML at line/ }).waitFor();
  await instructions.fill(first);
  await button("Continue").click();
  await addEscalation("demo-operations");
  await button("Continue").click();
  await button("Create runbook").click();
  await button("Edit " + name).waitFor();

  await page.getByRole("navigation").getByRole("button", { name: /^Rules/ }).click();
  await button("Create rule").first().click();
  await page.getByLabel("Rule name").fill(name);
  await page.getByLabel("Field name").fill("editorTest");
  await page.getByLabel("Value", { exact: true }).fill(name);
  await button("Continue").click();
  await page.getByRole("combobox", { name: "Teams" }).fill("demo-operations");
  await button("demo-operations").click();
  await page.getByLabel("Alert title").fill(name + " {{payload.sample}}");
  await page.getByRole("combobox", { name: /Runbook/ }).selectOption(name);
  await page.getByText("Noise control & advanced settings", { exact: true }).click();
  await page.getByLabel("Deduplication fields").fill("payload.sample");
  await page.getByLabel("Deduplication fields").press("Enter");
  await button("Continue").click();
  const savingRule = page.waitForResponse(response => response.url().endsWith("/api/rules") && response.request().method() === "POST");
  await button("Create rule").click();
  const rule = await savingRule;
  if (rule.status() !== 201) throw Error("Rule creation failed: " + await rule.text());
  const ruleId = (await rule.json()).id;
  await button("Runbooks").click();

  async function generateAlert(label, expected) {
    const engine = new URL(panel);
    engine.port = process.env.ENGINE_PORT || "8081";
    for (let attempt = 0; attempt < 12; attempt++) {
      const sample = label + "-" + attempt;
      const accepted = await page.request.post(engine.origin + "/ingest", { data: { editorTest: name, sample } });
      if (accepted.status() !== 202) throw Error("Ingest failed: " + await accepted.text());
      const event = await accepted.json();
      for (let poll = 0; poll < 8; poll++) {
        const feed = await (await page.request.get(panel + "/api/monitor/alerts")).json();
        const alert = feed.find(item => item.eventId === event.eventId && item.ruleId === ruleId);
        if (alert) {
          if (alert.runbook.content === expected) return alert;
          break;
        }
        await new Promise(resolve => setTimeout(resolve, 100));
      }
    }
    throw Error("Timed out waiting for the " + label + " runbook snapshot");
  }

  const before = await generateAlert("before", first);
  await page.setViewportSize({ width: 390, height: 844 });
  await button("Edit " + name).click();
  if (await instructions.inputValue() !== first) throw Error("YAML whitespace changed");
  await instructions.fill(second);
  await button("Continue").click();
  await page.getByLabel("Action type").selectOption("check_workflow_status");
  await button("Add action").click();
  await page.getByLabel("workflowId").fill("{{payload.sample}}");
  await button("Move up").last().click();
  await button("Continue").click();
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw Error("Editor overflows mobile viewport");
  await button("Save changes").click();
  await button("Edit " + name).waitFor();
  const after = await generateAlert("after", second);
  if (before.runbook.id !== after.runbook.id || before.runbook.actions.length !== 1 ||
      after.runbook.actions.map(step => step.type).join(",") !== "check_workflow_status,escalate") throw Error("Wrong action snapshots");

  const monitor = new URL(panel);
  monitor.port = process.env.MONITOR_PORT || "8080";
  await page.goto(monitor.origin);
  await page.getByRole("tab", { name: "Alerts", exact: true }).click();
  for (const alert of [before, after]) {
    await page.getByRole("button", { name: new RegExp(alert.title) }).click();
    const content = await page.locator(".detail-pane pre").allTextContents();
    if (!content.includes(alert.runbook.content)) throw Error("Monitor did not preserve " + alert.title);
  }
}
