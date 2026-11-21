async page => {
  const base = new URL(page.url()).origin;
  const teams = () => page.getByRole("navigation").getByRole("button", {name:"Teams", exact:true}).click();
  const team = page.getByRole("combobox", {name:"Team", exact:true});
  await page.goto(base + "/rules/new");
  await page.getByRole("heading", {name:"Log in to create a rule"}).waitFor();
  await page.waitForURL("**/#/login?next=rules/new");
  if (await page.getByLabel("Rule name").count()) throw Error("Guest has access to wizard");
  await page.getByRole("button", {name:"Demo Member", exact:true}).click();
  const name = "Ownership demo " + Date.now();
  await page.getByLabel("Rule name").fill(name);
  await page.getByLabel("Field name").fill("ownershipDemo");
  await page.getByLabel("Value",{exact:true}).fill(name);
  await page.getByRole("button",{name:"Continue"}).click();
  await page.getByRole("combobox",{name:"Teams"}).fill("demo-operations");
  await page.getByRole("button",{name:"demo-operations",exact:true}).click();
  await page.getByRole("button",{name:"Continue"}).click();
  const saved = page.waitForResponse(res => res.request().method() === "POST" && res.url().endsWith("/api/rules"));
  await page.getByRole("button",{name:"Create rule",exact:true}).click();
  const response = await saved;
  if (response.status() !== 201 || (await response.json()).createdBy.username !== "demo.member") throw Error("Invalid rule owner");
  await page.getByLabel("Search rules").fill(name);
  await page.locator(".rule-list-item").getByText(/Owner: demo.member/).waitFor();
  await page.locator(".detail-panel").getByText("Owner: demo.member").waitFor();
  await page.reload();
  await page.locator(".account-menu").getByText("demo.member",{exact:true}).waitFor();
  const monitor = await page.context().newPage();
  try {
    const monitorUrl = new URL(base);
    monitorUrl.port = process.env.MONITOR_PORT || "8080";
    await monitor.goto(monitorUrl.href);
    await monitor.locator(".account-menu").getByText("demo.member",{exact:true}).waitFor();
    await monitor.getByRole("tab",{name:"Rules",exact:true}).click();
    await monitor.getByRole("button",{name: new RegExp(name)}).click();
    await monitor.locator(".detail-pane").getByText("Owner: demo.member",{exact:true}).waitFor();
  } finally { await monitor.close(); }

  await teams();
  await team.selectOption({label:"demo-operations"});
  if (await page.getByRole("button",{name:/^Remove /}).count() || await page.getByLabel("Find a user to assign").count())
    throw Error("Member can see editing controls");
  await page.getByRole("button",{name:"Log out"}).click();
  await page.getByRole("heading",{name:"Log in to view teams"}).waitFor();
  await page.getByRole("button",{name:"Demo Team Leader"}).click();
  await page.getByRole("heading", {name:"Members of demo-operations"}).waitFor();
  if (await page.getByText("Goldman Alerting · Control service", {exact:true}).count()) throw Error("Unexpected panel footer");
  if (await team.count()) throw Error("Leader was asked to select a team");
  if (await page.getByRole("button", {name:"Remove demo.leader", exact:true}).count()) throw Error("Lead assignment is editable");
  const picker = page.getByLabel("Find a user to assign");
  // Restore the demo membership if someone was already using the analyst fixture.
  const remove = page.getByRole("button",{name:"Remove demo.analyst",exact:true});
  const wasAssigned = await remove.count() > 0;
  if (wasAssigned) await remove.click();
  try {
    await picker.fill("demo.analyst");
    await page.getByRole("button",{name:"Assign demo.analyst",exact:true}).click();
    await remove.waitFor();
    await page.reload();
    await teams(); await page.getByRole("heading", {name:"Members of demo-operations"}).waitFor();
    await remove.click();
    await remove.waitFor({state:"detached"});
  } finally {
    if (wasAssigned && !(await remove.count())) {
      await picker.fill("demo.analyst");
      await page.getByRole("button",{name:"Assign demo.analyst",exact:true}).click();
      await remove.waitFor();
    }
  }
  await page.getByRole("button",{name:"Log out"}).click();
  await page.getByRole("heading",{name:"Log in to view teams"}).waitFor();
  if ((await page.request.get(base + "/api/auth/me")).status() !== 401) throw Error("Logout left an active session");
}
