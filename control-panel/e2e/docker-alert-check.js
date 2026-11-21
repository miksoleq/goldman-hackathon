async page => {
  await page.getByLabel("Rule name").fill("UI check");
  await page.getByLabel("Field name").fill("amount");
  await page.getByLabel("Value", { exact: true }).fill("100");
  await page.getByRole("button", { name: "Continue" }).click();
  for (const heading of ["Recipients", "Alert content & appearance", "Response"]) {
    if (!(await page.getByRole("heading", { name: heading }).isVisible())) throw Error("Missing " + heading);
  }
  if (!(await page.getByLabel("Alert message").evaluate(element => element.tagName === "TEXTAREA"))) throw Error("Message is not multiline");
  return "Redesigned alert settings visible";
}
