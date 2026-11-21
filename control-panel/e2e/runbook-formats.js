async page => {
  const base = new URL(page.url()).origin;
  const button = name => page.getByRole("button", { name, exact: true });
  const content = page.getByLabel("Instructions", { exact: true });
  const name = "Formats " + Date.now();
  const text = "Inspect the queue.\nRecord the outcome.";
  const markdown = "## Queue check\n\n**Record** the outcome.\n\n<script>throw Error('unexpected script')</script>";
  async function review() {
    await button("Continue").click();
    await button("Continue").click();
  }
  await button("Runbooks").click();
  await button("Create runbook").click();
  await page.getByLabel("Runbook name").fill(name);
  await content.fill(text);
  await review();
  await button("Create runbook").click();
  await button("Edit " + name).click();
  if (await content.inputValue() !== text) throw Error("Text was not restored");
  await page.getByLabel("Format").selectOption("markdown");
  await content.fill(markdown);
  await review();
  await page.getByRole("heading", { name: "Queue check" }).waitFor();
  if (await page.locator(".review script").count()) throw Error("Raw HTML rendered in preview");
  await button("Save changes").click();
  await button("Edit " + name).click();
  if (await content.inputValue() !== markdown) throw Error("Markdown was not restored");

  const books = await (await page.request.get(base + "/api/runbooks")).json();
  const id = books.find(book => book.name === name).id;
  const url = base + "/api/runbooks/" + id;
  const latest = await page.request.get(url);
  const concurrent = await page.request.put(url, { headers: { "If-Match": latest.headers().etag },
    data: { kind: "Runbook", name, spec: { format: "text", content: "Concurrent edit" } } });
  if (!concurrent.ok()) throw Error("Concurrent edit failed");
  await content.fill("Keep this draft");
  await review();
  await button("Save changes").click();
  await page.getByRole("alert").filter({ hasText: "Someone updated" }).waitFor();
  await button("Back").click();
  await button("Back").click();
  if (await content.inputValue() !== "Keep this draft") throw Error("Conflict discarded the draft");
  await button("Cancel").click();
  await button("Create runbook").click();
  await page.getByLabel("Runbook name").fill(name);
  await content.fill(text);
  await review();
  await button("Create runbook").click();
  await page.getByRole("alert").filter({ hasText: "name already exists" }).waitFor();
  if (await content.inputValue() !== text) throw Error("Duplicate name discarded the draft");
}
