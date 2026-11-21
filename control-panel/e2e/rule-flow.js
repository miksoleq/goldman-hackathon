async page => {
  await page.reload();
  const login = page.getByRole("button", { name: "Log in", exact: true });
  await page.getByRole("button", { name: /^(Log in|Log out)$/ }).first().waitFor();
  if (await login.isVisible()) {
    await login.click();
    await page.getByRole("button", { name: "Demo Member" }).click();
    await page.getByText("demo.member", { exact: true }).waitFor();
  }
  await page.getByRole("button", { name: "Create rule" }).first().click();
  await page.getByLabel("Rule name").fill("Browser test");
  const type = page.getByRole("combobox", { name: "Field type" }).first();
  const operator = page.getByRole("combobox", { name: "Condition", exact: true }).first();
  if (!(await operator.locator("option[value=gt]").count())) throw new Error("Numeric comparison missing for numeric strings");
  await type.selectOption("number");
  await operator.selectOption("gt");
  await page.getByLabel("Field name").first().fill("amount");
  await page.locator('input[type="number"]').first().fill("100");
  await page.getByRole("button", { name: "Add nested group" }).first().click();
  await page.getByRole("combobox", { name: "Match", exact: true }).last().selectOption("any");
  await page.getByLabel("Field name").last().fill("status");
  await page.getByLabel("Value", { exact: true }).last().fill("failed");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel("Teams").fill("payments");
  await page.getByRole("option", { name: "payments", exact: true }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  let sent;
  await page.route("**/api/rules", async route => {
    sent = route.request().postDataJSON();
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({id:"test"}) });
  });
  await page.getByRole("button", { name: "Create rule", exact: true }).click();
  await page.getByText("Rule created and saved in Control.").waitFor();
  if (sent.match.all[0].value !== 100 || sent.match.all[1].any[0].value !== "failed") throw new Error("Incorrect request");
  return sent;
}
