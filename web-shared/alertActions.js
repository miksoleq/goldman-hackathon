import { sessionRequest } from "./session.js";

function alertUrl(alertId) {
  return "/api/alerts/" + encodeURIComponent(alertId);
}

export async function fetchAlertDetails(alertId, signal) {
  const response = await sessionRequest(alertUrl(alertId), { cache: "no-store", signal });
  if (!response.ok) throw new Error("Could not load runbook actions.");
  return response.json();
}

export async function executeAction(alertId, stepNo, confirmed) {
  const response = await sessionRequest(alertUrl(alertId) + "/actions/" + stepNo + "/execute", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ confirmed })
  });
  if (!response.ok) throw new Error("Could not verify the action result. Refresh the alert to check its state.");
  return response.json();
}
