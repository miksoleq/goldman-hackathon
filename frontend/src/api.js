export const FEED_NAMES = ["messages", "rules", "runbooks", "alerts"];

const FEED_URLS = {
  messages: "/api/monitor/messages",
  rules: "/api/monitor/rules",
  runbooks: "/api/monitor/runbooks",
  alerts: "/api/monitor/alerts"
};

export async function fetchMonitorFeed(name, request = fetch) {
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
