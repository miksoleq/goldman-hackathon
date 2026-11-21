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
