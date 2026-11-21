export async function fetchFeed(name, request = fetch) {
  const response = await request("/api/monitor/" + name, {
    headers: { Accept: "application/json" },
    cache: "no-store"
  });
  if (!response.ok) throw new Error("Could not load " + name + ".");
  const items = await response.json();
  if (!Array.isArray(items)) throw new Error("Control returned an invalid " + name + " response.");
  return items;
}

export async function createRule(payload, request = fetch) {
  const response = await request("/api/rules", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(payload)
  });
  return savedRule(response);
}

async function savedRule(response) {
  if (response.ok) return response.json();
  const problem = await response.json().catch(() => ({}));
  if (problem.error === "runbook_name_exists") throw new Error("A runbook with this name already exists. Reference it by name or choose a new name.");
  if (response.status === 409) throw new Error("A rule with this name already exists.");
  throw new Error(problem.message || problem.error || "Control could not save this rule.");
}

export async function saveRuleYaml(source, rule, request = fetch) {
  const headers = { "Content-Type": "application/yaml", Accept: "application/json" };
  if (rule) headers["If-Match"] = '"' + rule.version + '"';
  const url = rule ? "/api/rules/" + encodeURIComponent(rule.id) : "/api/rules";
  return savedRule(await request(url, { method: rule ? "PUT" : "POST", headers, body: source }));
}
