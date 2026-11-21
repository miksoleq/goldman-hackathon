async page => {
  await page.route("**/api/teams", route => route.fulfill({ json: [{ name: "Market Risk" }, ...Array.from({ length: 12 }, (_, index) => ({ name: "Market " + index })), { name: "Operations" }] }));
  await page.route("**/api/users", route => route.fulfill({ json: [{ username: "analyst" }] }));
  await page.reload();
  const login = page.getByRole("button", { name: "Log in", exact: true });
  await page.getByRole("button", { name: /^(Log in|Log out)$/ }).first().waitFor();
  if (await login.isVisible()) {
    await login.click();
    await page.getByRole("button", { name: "Demo Member" }).click();
    await page.getByText("demo.member", { exact: true }).waitFor();
  }
  await page.getByRole("button", { name: "Create rule" }).first().click();
  await page.getByLabel("Rule name").fill("Alert test");
  await page.getByLabel("Field name").fill("service");
  await page.getByLabel("Value", { exact: true }).fill("payments");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  if (!(await page.getByRole("alert").innerText()).includes("at least one")) throw Error("Missing recipient validation");
  await page.getByRole("combobox", { name: "Teams" }).fill("Ma");
  if (await page.getByRole("option", { name: "Market Risk" }).count()) throw Error("Suggestions shown before three characters");
  await page.getByRole("combobox", { name: "Teams" }).fill("Mar");
  if (await page.getByRole("listbox", { name: "Teams results" }).getByRole("option").count() !== 10) throw Error("Suggestion limit is not ten");
  await page.getByRole("button", { name: "Market Risk", exact: true }).click();
  await page.getByRole("combobox", { name: "Users" }).fill("analyst");
  await page.getByRole("button", { name: "analyst", exact: true }).click();
  await page.getByLabel("Alert message").fill("Service ");
  await page.getByRole("button", { name: "{{payload.service}}" }).click();
  if (!(await page.getByLabel("Alert message").inputValue()).includes("{{payload.service}}")) throw Error("Variable insertion failed");
  await page.getByText("Noise control & advanced settings").click();
  await page.getByLabel("Deduplication fields").fill("payload.service");
  await page.getByLabel("Deduplication fields").press("Enter");
  await page.getByRole("button", { name: "Continue" }).click();
  let payload;
  await page.route("**/api/rules", async route => {
    payload = route.request().postDataJSON();
    await route.fulfill({ status: 201, json: { id: "test" } });
  });
  await page.getByRole("button", { name: "Create rule", exact: true }).click();
  await page.getByText("Rule created and saved in Control.").waitFor();
  if (payload.route.teams[0] !== "Market Risk" || payload.route.users[0] !== "analyst" || payload.dedup.keys[0] !== "payload.service") throw Error("Incorrect API payload");
  return "Recipients, template insertion, deduplication and payload verified";
}
