import { sessionRequest } from "../../web-shared/session.js";

export async function fetchFeed(name, request = sessionRequest) {
  const response = await request("/api/monitor/" + name, {
    headers: { Accept: "application/json" },
    cache: "no-store"
  });
  if (!response.ok) throw new Error("Could not load " + name + ".");
  const items = await response.json();
  if (!Array.isArray(items)) throw new Error("Control returned an invalid " + name + " response.");
  return items;
}

export async function createRule(payload, request = sessionRequest) {
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

export async function saveRuleYaml(source, rule, request = sessionRequest) {
  const headers = { "Content-Type": "application/yaml", Accept: "application/json" };
  if (rule) headers["If-Match"] = '"' + rule.version + '"';
  const url = rule ? "/api/rules/" + encodeURIComponent(rule.id) : "/api/rules";
  return savedRule(await request(url, { method: rule ? "PUT" : "POST", headers, body: source }));
}

export async function loadRunbookEditor(id, signal, request = sessionRequest) {
  const options = { cache: "no-store", signal };
  const [catalog, detail] = await Promise.all([
    request("/api/action-types", options),
    id ? request("/api/runbooks/" + encodeURIComponent(id), options) : null
  ]);
  if (!catalog.ok || (detail && !detail.ok)) throw new Error("Could not load the runbook editor.");
  const types = await catalog.json();
  const book = detail ? await detail.json() : null;
  const etag = detail?.headers.get("ETag");
  if (!Array.isArray(types) || (id && (book?.id !== id || !etag || !Array.isArray(book.actions)))) {
    throw new Error("Control returned incomplete editor data.");
  }
  return { book, types, etag };
}

export async function saveRunbook(id, etag, resource, request = sessionRequest) {
  const response = await request(id ? "/api/runbooks/" + encodeURIComponent(id) : "/api/apply", {
    method: id ? "PUT" : "POST",
    headers: { "Content-Type": "application/json", ...(id && { "If-Match": etag }) },
    body: JSON.stringify(resource)
  });
  if (response.ok) return response.json();
  if (response.status === 409) throw new Error("A runbook with this name already exists.");
  if (response.status === 412) throw new Error("Someone updated this runbook. Copy your changes and reopen the editor to load the latest version.");
  const problem = await response.json().catch(() => ({}));
  throw new Error(problem.message || "Could not save the runbook.");
}
