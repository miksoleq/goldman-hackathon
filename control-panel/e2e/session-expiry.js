async page => {
  const base = new URL(page.url()).origin;
  await page.getByRole("button", {name:"Create rule", exact:true}).first().click();
  await page.getByLabel("Rule name").fill("Expired session");
  await page.getByLabel("Field name").fill("service");
  await page.getByLabel("Value", {exact:true}).fill("example");
  await page.getByRole("button",{name:"Continue"}).click();
  await page.getByRole("combobox",{name:"Teams"}).fill("demo-operations");
  await page.getByRole("button",{name:"demo-operations",exact:true}).click();
  await page.getByRole("button",{name:"Continue"}).click();
  await page.request.post(base + "/api/auth/logout");
  const response = page.waitForResponse(res => res.request().method() === "POST" && res.url().endsWith("/api/rules"));
  await page.getByRole("button",{name:"Create rule",exact:true}).click();
  if ((await response).status() !== 401) throw Error("Expired cookie authorized a write");
  await page.getByRole("heading",{name:"Log in to create a rule"}).waitFor();
  if (await page.getByLabel("Rule name").count()) throw Error("Expired session kept wizard open");
}
