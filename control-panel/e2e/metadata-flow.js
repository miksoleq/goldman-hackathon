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
  await page.getByLabel("Look in").selectOption("meta");
  if (await page.getByLabel("Field type").count()) throw new Error("Metadata type selector is visible");
  const field = page.getByLabel("Field name");
  const operator = page.getByRole("combobox", { name: "Condition", exact: true });
  await field.selectOption("source");
  if (await operator.locator("option[value=gt]").count()) throw new Error("Text metadata has numeric operators");
  await operator.selectOption("regex");
  await field.selectOption("receivedAt");
  if (!await operator.locator("option[value=gt]").count()) throw new Error("Timestamp lacks numeric operators");
  if (await operator.locator("option[value=regex]").count()) throw new Error("Timestamp has text operators");
  if (await operator.inputValue() !== "eq") throw new Error("Incompatible operator was not reset");
  await field.selectOption("adapter");
  if (await operator.locator("option[value=gt]").count()) throw new Error("Numeric operator remains on text field");
  return "Metadata operator selection passed";
}
