async page => {
  await page.goto("http://127.0.0.1:8083/");
  await page.getByRole("button", { name: "Create rule" }).first().click();
  await page.getByLabel("Rule name").fill("Suggestion check");
  await page.getByLabel("Field name").fill("amount");
  await page.getByLabel("Value", { exact: true }).fill("10");
  await page.getByRole("button", { name: "Continue" }).click();
  const teams = page.getByRole("combobox", { name: "Teams" });
  await teams.fill("de");
  if (await page.getByRole("listbox", { name: "Teams results" }).count()) throw Error("Suggestions appeared too early");
  await teams.fill("dem");
  await page.getByRole("button", { name: "demo-market-risk", exact: true }).waitFor();
  await page.getByRole("button", { name: "demo-market-risk", exact: true }).click();
  const users = page.getByRole("combobox", { name: "Users" });
  await users.fill("demo");
  await page.getByRole("button", { name: "demo-alex", exact: true }).waitFor();
  return "Live recipient suggestions verified";
}
