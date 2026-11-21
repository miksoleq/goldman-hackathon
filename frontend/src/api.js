import { sessionRequest } from "../../web-shared/session.js";

export const FEED_NAMES = ["rules", "runbooks", "alerts"];

const FEED_URLS = {
  rules: "/api/monitor/rules",
  runbooks: "/api/monitor/runbooks",
  alerts: "/api/monitor/alerts"
};

export async function fetchMonitorFeed(name, request = sessionRequest) {
  const url = FEED_URLS[name];
  if (!url) throw new TypeError("Unknown monitor feed.");

  const response = await request(url, {
    headers: { Accept: "application/json" },
    cache: "no-store"
  });
  if (!response.ok) throw new Error("Monitor feed request failed.");

  const items = await response.json();
  if (!Array.isArray(items)) throw new Error("Monitor feed returned an invalid response.");
  return items;
}

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
