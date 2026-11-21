async page => {
  await page.reload();
  await page.getByRole("button", { name: "Create rule" }).first().click();
  await page.getByRole("button", { name: "Continue" }).click();
  if (await page.getByRole("alert").innerText() !== "Enter a rule name.") throw new Error("Expected English validation message");
  await page.getByLabel("Rule name").fill("Validation check");
  await page.getByRole("button", { name: "Continue" }).click();
  if (await page.getByRole("alert").innerText() !== "Enter a field name for each condition.") throw new Error("Expected English field validation");
  return "English validation messages verified";
}
